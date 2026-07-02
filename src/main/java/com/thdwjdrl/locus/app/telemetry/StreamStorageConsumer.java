package com.thdwjdrl.locus.app.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thdwjdrl.locus.core.domain.Telemetry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Stream 적재 컨슈머 (M4b B — {@code storage} 컨슈머 그룹). {@link StreamIngestWriter}가 XADD한 텔레메트리를 그룹으로 읽어
 * 배치 적재한 뒤 XACK한다 = 인메모리 배치워커(A2)의 Stream 판.
 *
 * <p>전용 스레드 {@code workers}개가 각자 다른 consumer 이름으로 XREADGROUP(BLOCK+COUNT)해 배치를 모으고 {@link
 * TelemetryBatchDao}로 적재. **적재 성공 후에만 XACK** → 크래시로 XACK 전에 죽으면 pending으로 남아 재처리(at-least-once).
 * 적재는 {@code ON CONFLICT DO NOTHING}이라 멱등 → 재처리해도 중복 안 생김.
 *
 * <p>메트릭은 배치워커와 같은 이름({@code locus.ingest.*})이라 대시보드 재사용.
 */
@Component
@ConditionalOnProperty(name = "locus.ingest.mode", havingValue = "stream")
public class StreamStorageConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(StreamStorageConsumer.class);
    static final String GROUP = "storage";

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final TelemetryBatchDao dao;
    private final IngestProperties props;

    private final Timer flushTimer;
    private final Counter inserted;
    private final Counter flushErrors;

    private volatile boolean running = false;
    private final List<Thread> workers = new ArrayList<>();

    public StreamStorageConsumer(
            StringRedisTemplate redis,
            ObjectMapper json,
            TelemetryBatchDao dao,
            IngestProperties props,
            MeterRegistry meters) {
        this.redis = redis;
        this.json = json;
        this.dao = dao;
        this.props = props;
        this.flushTimer =
                Timer.builder("locus.ingest.flush").description("배치 적재 1회 소요").register(meters);
        this.inserted =
                Counter.builder("locus.ingest.inserted")
                        .description("적재 시도한 텔레메트리 행 수(중복 IGNORE 포함)")
                        .register(meters);
        this.flushErrors =
                Counter.builder("locus.ingest.flush.errors")
                        .description("배치 적재 실패 횟수")
                        .register(meters);
    }

    @Override
    public void start() {
        ensureGroup();
        running = true;
        int n = Math.max(1, props.getWorkers());
        for (int i = 0; i < n; i++) {
            String consumer = "storage-" + i;
            Thread t = new Thread(() -> runLoop(consumer), "stream-storage-" + i);
            t.setDaemon(true);
            t.start();
            workers.add(t);
        }
        log.info("Stream storage 컨슈머 {}개 시작 (stream={}, group={})", n, props.getStreamKey(), GROUP);
    }

    /** 컨슈머 그룹 생성(MKSTREAM). 이미 있으면(BUSYGROUP) 무시 — 멱등. offset 0 = 스트림 처음부터. */
    private void ensureGroup() {
        try {
            redis.opsForStream().createGroup(props.getStreamKey(), ReadOffset.from("0"), GROUP);
        } catch (Exception e) {
            log.info("storage 그룹 생성 스킵(이미 존재 가능): {}", e.toString());
        }
    }

    private void runLoop(String consumer) {
        while (running) {
            try {
                List<MapRecord<String, Object, Object>> records =
                        redis.opsForStream()
                                .read(
                                        Consumer.from(GROUP, consumer),
                                        StreamReadOptions.empty()
                                                .count(props.getBatchSize())
                                                .block(Duration.ofMillis(props.getMaxDelayMs())),
                                        StreamOffset.create(
                                                props.getStreamKey(), ReadOffset.lastConsumed()));
                if (records == null || records.isEmpty()) {
                    continue; // BLOCK 타임아웃 — 다시 대기
                }
                flush(records);
            } catch (RuntimeException e) {
                log.warn("storage 소비 실패: {}", e.toString());
                sleep(200); // 폭주 방지
            }
        }
    }

    private void flush(List<MapRecord<String, Object, Object>> records) {
        List<Telemetry> batch = new ArrayList<>(records.size());
        for (MapRecord<String, Object, Object> rec : records) {
            String data = (String) rec.getValue().get(StreamIngestWriter.PAYLOAD_FIELD);
            batch.add(read(data));
        }
        try {
            flushTimer.record(() -> dao.persistBatch(batch));
            inserted.increment(batch.size());
            ack(records); // 적재 성공 후에만 XACK
        } catch (RuntimeException e) {
            // 적재 실패 → XACK 안 함 → pending 유지 → 재처리(at-least-once). 워커는 안 죽인다.
            flushErrors.increment();
            log.warn("배치 적재 실패({}건, XACK 안 함 → 재처리 대기): {}", batch.size(), e.toString());
        }
    }

    private void ack(List<MapRecord<String, Object, Object>> records) {
        RecordId[] ids = records.stream().map(MapRecord::getId).toArray(RecordId[]::new);
        redis.opsForStream().acknowledge(props.getStreamKey(), GROUP, ids);
    }

    private Telemetry read(String jsonPayload) {
        try {
            return json.readValue(jsonPayload, TelemetryResponse.class).toTelemetry();
        } catch (Exception e) {
            throw new IllegalStateException("스트림 페이로드 역직렬화 실패", e);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop() {
        running = false;
        for (Thread t : workers) {
            t.interrupt();
        }
        workers.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
