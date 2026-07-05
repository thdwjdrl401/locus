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
    private final Counter poisonDropped;

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
        this.poisonDropped =
                Counter.builder("locus.ingest.poison")
                        .description("역직렬화 불가로 드롭한 스트림 엔트리 수(트림된 유령·손상 payload)")
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
        recoverPending(consumer); // 재시작 시 미ACK pending 먼저 재처리(at-least-once)
        while (running) {
            try {
                List<MapRecord<String, Object, Object>> records =
                        readGroup(consumer, ReadOffset.lastConsumed(), true);
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

    /**
     * 재시작 시 이 컨슈머의 미ACK pending(PEL)을 먼저 비운다 — 크래시로 XACK 전에 죽은 배치 재처리(at-least-once). offset {@code
     * "0"} = 이 컨슈머에게 이미 배달됐으나 ACK 안 된 것. 적재는 {@code ON CONFLICT}라 멱등 → 중복 재처리해도 안전.
     */
    private void recoverPending(String consumer) {
        long recovered = 0;
        while (running) {
            List<MapRecord<String, Object, Object>> pending;
            try {
                pending = readGroup(consumer, ReadOffset.from("0"), false);
            } catch (RuntimeException e) {
                log.warn("{} pending 회수 실패: {}", consumer, e.toString());
                return;
            }
            if (pending == null || pending.isEmpty()) {
                break;
            }
            flush(pending);
            recovered += pending.size();
        }
        if (recovered > 0) {
            log.info("{} 재시작 pending {}건 재처리", consumer, recovered);
        }
    }

    private List<MapRecord<String, Object, Object>> readGroup(
            String consumer, ReadOffset offset, boolean block) {
        StreamReadOptions opts = StreamReadOptions.empty().count(props.getBatchSize());
        if (block) {
            opts = opts.block(Duration.ofMillis(props.getMaxDelayMs()));
        }
        return redis.opsForStream()
                .read(
                        Consumer.from(GROUP, consumer),
                        opts,
                        StreamOffset.create(props.getStreamKey(), offset));
    }

    private void flush(List<MapRecord<String, Object, Object>> records) {
        List<Telemetry> batch = new ArrayList<>(records.size());
        List<RecordId> goodIds = new ArrayList<>(records.size());
        List<RecordId> poisonIds = new ArrayList<>();
        for (MapRecord<String, Object, Object> rec : records) {
            Telemetry t = tryRead(rec);
            if (t == null) {
                poisonIds.add(rec.getId()); // 역직렬화 불가(트림된 유령·손상 payload) → 드롭 대상
            } else {
                batch.add(t);
                goodIds.add(rec.getId());
            }
        }
        if (!poisonIds.isEmpty()) {
            // 처리 불가 엔트리는 XACK로 버린다 — 안 버리면 pending에 남아 재시작마다 무한 재처리(워커 정지 원인).
            acknowledge(poisonIds);
            poisonDropped.increment(poisonIds.size());
            log.warn("스트림 poison {}건 드롭(역직렬화 불가 — 트림된 유령·손상 payload)", poisonIds.size());
        }
        if (batch.isEmpty()) {
            return;
        }
        try {
            flushTimer.record(() -> dao.persistBatch(batch));
            inserted.increment(batch.size());
            acknowledge(goodIds); // 적재 성공 후에만 XACK
        } catch (RuntimeException e) {
            // 적재 실패 → XACK 안 함 → pending 유지 → 재처리(at-least-once). 워커는 안 죽인다.
            flushErrors.increment();
            log.warn("배치 적재 실패({}건, XACK 안 함 → 재처리 대기): {}", batch.size(), e.toString());
        }
    }

    private void acknowledge(List<RecordId> ids) {
        redis.opsForStream().acknowledge(props.getStreamKey(), GROUP, ids.toArray(new RecordId[0]));
    }

    /**
     * payload 역직렬화. 실패(payload null=트림된 유령, 손상 JSON)면 {@code null} 반환 — 호출부가 드롭한다. 던지지 않아 워커가 안
     * 죽는다.
     */
    private Telemetry tryRead(MapRecord<String, Object, Object> rec) {
        Object data = rec.getValue().get(StreamIngestWriter.PAYLOAD_FIELD);
        if (data == null) {
            return null;
        }
        try {
            return json.readValue(data.toString(), TelemetryResponse.class).toTelemetry();
        } catch (Exception e) {
            log.warn("스트림 payload 역직렬화 실패(id={}): {}", rec.getId(), e.toString());
            return null;
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
