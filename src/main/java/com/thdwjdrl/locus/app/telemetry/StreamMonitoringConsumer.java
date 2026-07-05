package com.thdwjdrl.locus.app.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
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
 * Stream 모니터링 컨슈머 (M4b B — {@code monitoring} 컨슈머 그룹). 같은 Stream을 별도 그룹으로 읽어 실시간 push한다.
 *
 * <p>**push가 적재 핫패스({@link DirectIngestWriter})에서 여기로 이동** — 오늘 direct 모드 풀 고갈의 근본 해결. 적재는 {@link
 * StreamStorageConsumer}(storage 그룹), push는 여기(monitoring 그룹), 서로 독립 커서라 각자 모든 메시지를 본다.
 *
 * <p>org는 여기서 {@link DeviceOrgResolver}로 푼다(적재 경로 아님). 그룹을 최신(`$`)부터 만들어 과거를 replay push하지 않는다.
 * push 중복은 지도에서 무해(같은/최신 위치로 덮어씀)하므로 push 후 XACK. 순서 보존 위해 단일 워커.
 */
@Component
@ConditionalOnProperty(name = "locus.ingest.mode", havingValue = "stream")
public class StreamMonitoringConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(StreamMonitoringConsumer.class);
    static final String GROUP = "monitoring";
    private static final int READ_COUNT = 200;

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final IngestProperties props;
    private final DeviceOrgResolver orgs;
    private final LiveUpdatePublisher publisher;
    private final Counter pushed;
    private final Counter poisonDropped;

    private volatile boolean running = false;
    private Thread worker;

    public StreamMonitoringConsumer(
            StringRedisTemplate redis,
            ObjectMapper json,
            IngestProperties props,
            DeviceOrgResolver orgs,
            LiveUpdatePublisher publisher,
            MeterRegistry meters) {
        this.redis = redis;
        this.json = json;
        this.props = props;
        this.orgs = orgs;
        this.publisher = publisher;
        this.pushed =
                Counter.builder("locus.push.sent")
                        .description("실시간 push한 텔레메트리 수")
                        .register(meters);
        this.poisonDropped =
                Counter.builder("locus.push.poison")
                        .description("역직렬화 불가로 push 스킵·드롭한 스트림 엔트리 수")
                        .register(meters);
    }

    @Override
    public void start() {
        ensureGroup();
        running = true;
        worker = new Thread(this::runLoop, "stream-monitoring");
        worker.setDaemon(true);
        worker.start();
        log.info("Stream monitoring 컨슈머 시작 (stream={}, group={})", props.getStreamKey(), GROUP);
    }

    /** monitoring 그룹 생성(MKSTREAM). 최신($)부터 — 과거 replay push 방지. 이미 있으면 무시. */
    private void ensureGroup() {
        try {
            redis.opsForStream().createGroup(props.getStreamKey(), ReadOffset.latest(), GROUP);
        } catch (Exception e) {
            log.info("monitoring 그룹 생성 스킵(이미 존재 가능): {}", e.toString());
        }
    }

    private void runLoop() {
        while (running) {
            try {
                List<MapRecord<String, Object, Object>> records =
                        redis.opsForStream()
                                .read(
                                        Consumer.from(GROUP, "monitoring-0"),
                                        StreamReadOptions.empty()
                                                .count(READ_COUNT)
                                                .block(Duration.ofMillis(props.getMaxDelayMs())),
                                        StreamOffset.create(
                                                props.getStreamKey(), ReadOffset.lastConsumed()));
                if (records == null || records.isEmpty()) {
                    continue;
                }
                for (MapRecord<String, Object, Object> rec : records) {
                    pushOne(rec); // poison이어도 던지지 않는다 — 배치 중단·정상 메시지 starvation 방지
                }
                // 처리했으면 성공·poison 드롭 무관 전부 XACK — poison도 버려야 PEL에 안 남고 재처리 안 됨.
                RecordId[] ids = records.stream().map(MapRecord::getId).toArray(RecordId[]::new);
                redis.opsForStream().acknowledge(props.getStreamKey(), GROUP, ids);
            } catch (RuntimeException e) {
                log.warn("monitoring 소비 실패: {}", e.toString());
                sleep(200);
            }
        }
    }

    private void pushOne(MapRecord<String, Object, Object> rec) {
        TelemetryResponse resp = tryRead(rec);
        if (resp == null) {
            poisonDropped.increment(); // 역직렬화 불가 → push 스킵, 상위 XACK로 드롭
            return;
        }
        String org = orgs.orgOf(resp.deviceId());
        if (org == null) {
            return; // 미enroll — 라우팅할 조직 없음
        }
        publisher.publish(org, resp);
        pushed.increment();
    }

    /** payload 역직렬화. 실패(payload null=트림된 유령, 손상 JSON)면 {@code null} 반환 — 던지지 않아 배치·워커가 안 죽는다. */
    private TelemetryResponse tryRead(MapRecord<String, Object, Object> rec) {
        Object data = rec.getValue().get(StreamIngestWriter.PAYLOAD_FIELD);
        if (data == null) {
            return null;
        }
        try {
            return json.readValue(data.toString(), TelemetryResponse.class);
        } catch (Exception e) {
            log.warn("monitoring payload 역직렬화 실패(id={}): {}", rec.getId(), e.toString());
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
        if (worker != null) {
            worker.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
