package com.thdwjdrl.locus.app.geofence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thdwjdrl.locus.app.telemetry.DeviceOrgResolver;
import com.thdwjdrl.locus.app.telemetry.IngestProperties;
import com.thdwjdrl.locus.app.telemetry.StreamIngestWriter;
import com.thdwjdrl.locus.app.telemetry.TelemetryResponse;
import com.thdwjdrl.locus.core.domain.Location;
import com.thdwjdrl.locus.core.domain.ReachTransition;
import com.thdwjdrl.locus.core.engine.RadiusEvaluator;
import com.thdwjdrl.locus.core.strategy.ReachEvaluator;
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
 * Stream 지오펜스 판정 컨슈머 (M5 — {@code geofence} 컨슈머 그룹, ADR 0007 세 번째 소비자). 같은 {@code
 * telemetry.stream}을 storage·monitoring과 독립 커서로 읽어 각 디바이스 위치를 org의 지오펜스에 대해 판정하고, ENTER/EXIT 전이만
 * 이벤트로 push한다.
 *
 * <p>{@link com.thdwjdrl.locus.app.telemetry.StreamMonitoringConsumer} 구조를 따른다: 단일 워커(디바이스별 상태 전이라
 * 순서 보존), 그룹은 {@code $}(최신)부터 — 과거 replay 안 함, {@code tryRead} poison 내성(트림된 유령·손상 JSON은 스킵). 판정
 * 기하는 {@link RadiusEvaluator}(core, 순수), 상태는 {@link GeofenceStateStore} 포트.
 */
@Component
@ConditionalOnProperty(name = "locus.ingest.mode", havingValue = "stream")
public class StreamGeofenceConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(StreamGeofenceConsumer.class);
    static final String GROUP = "geofence";
    private static final int READ_COUNT = 200;

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final IngestProperties props;
    private final DeviceOrgResolver orgs;
    private final GeofenceCatalog catalog;
    private final GeofenceStateStore store;
    private final GeofenceEventPublisher publisher;
    private final ReachEvaluator evaluator = new RadiusEvaluator();
    private final Counter enterCount;
    private final Counter exitCount;
    private final Counter poisonDropped;

    private volatile boolean running = false;
    private Thread worker;

    public StreamGeofenceConsumer(
            StringRedisTemplate redis,
            ObjectMapper json,
            IngestProperties props,
            DeviceOrgResolver orgs,
            GeofenceCatalog catalog,
            GeofenceStateStore store,
            GeofenceEventPublisher publisher,
            MeterRegistry meters) {
        this.redis = redis;
        this.json = json;
        this.props = props;
        this.orgs = orgs;
        this.catalog = catalog;
        this.store = store;
        this.publisher = publisher;
        this.enterCount =
                Counter.builder("locus.geofence.events")
                        .tag("type", "ENTER")
                        .description("지오펜스 진입 이벤트 수")
                        .register(meters);
        this.exitCount =
                Counter.builder("locus.geofence.events")
                        .tag("type", "EXIT")
                        .description("지오펜스 이탈 이벤트 수")
                        .register(meters);
        this.poisonDropped =
                Counter.builder("locus.geofence.poison")
                        .description("역직렬화 불가로 스킵한 스트림 엔트리 수")
                        .register(meters);
    }

    @Override
    public void start() {
        if (catalog.all().isEmpty()) {
            // 판정할 지오펜스가 없으면 스트림을 읽지 않는다 — 불필요한 소비·contention 방지(§3.2).
            log.info("Stream geofence 컨슈머 미가동 (시드 지오펜스 0)");
            return;
        }
        ensureGroup();
        running = true;
        worker = new Thread(this::runLoop, "stream-geofence");
        worker.setDaemon(true);
        worker.start();
        log.info(
                "Stream geofence 컨슈머 시작 (stream={}, group={}, zones={})",
                props.getStreamKey(),
                GROUP,
                catalog.all().size());
    }

    /** 그룹 생성(최신 $부터 — 과거 replay 방지). 이미 있으면(BUSYGROUP) 무시. */
    private void ensureGroup() {
        try {
            redis.opsForStream().createGroup(props.getStreamKey(), ReadOffset.latest(), GROUP);
        } catch (Exception e) {
            log.info("geofence 그룹 생성 스킵(이미 존재 가능): {}", e.toString());
        }
    }

    private void runLoop() {
        while (running) {
            try {
                List<MapRecord<String, Object, Object>> records =
                        redis.opsForStream()
                                .read(
                                        Consumer.from(GROUP, "geofence-0"),
                                        StreamReadOptions.empty()
                                                .count(READ_COUNT)
                                                .block(Duration.ofMillis(props.getMaxDelayMs())),
                                        StreamOffset.create(
                                                props.getStreamKey(), ReadOffset.lastConsumed()));
                if (records == null || records.isEmpty()) {
                    continue;
                }
                for (MapRecord<String, Object, Object> rec : records) {
                    evaluate(rec);
                }
                RecordId[] ids = records.stream().map(MapRecord::getId).toArray(RecordId[]::new);
                redis.opsForStream().acknowledge(props.getStreamKey(), GROUP, ids);
            } catch (RuntimeException e) {
                log.warn("geofence 소비 실패: {}", e.toString());
                sleep(200);
            }
        }
    }

    private void evaluate(MapRecord<String, Object, Object> rec) {
        TelemetryResponse resp = tryRead(rec);
        if (resp == null) {
            poisonDropped.increment();
            return;
        }
        TelemetryResponse.LocationDto loc = resp.location();
        if (loc == null || loc.lat() == null || loc.lng() == null) {
            return; // 위치 없음(프라이버시 게이트) → 판정 불가, 스킵
        }
        String org = orgs.orgOf(resp.deviceId());
        if (org == null) {
            return; // 미enroll
        }
        List<Geofence> zones = catalog.zonesForOrg(org);
        if (zones.isEmpty()) {
            return;
        }
        Location pos =
                new Location(
                        loc.lat(),
                        loc.lng(),
                        loc.accuracy(),
                        loc.altitude(),
                        loc.speed(),
                        loc.heading());
        for (Geofence z : zones) {
            boolean now = evaluator.isInside(pos, z.centerLat(), z.centerLng(), z.radiusMeters());
            ReachTransition tr = ReachTransition.of(store.inside(resp.deviceId(), z.id()), now);
            if (tr.isCrossing()) {
                String type = tr == ReachTransition.ENTER ? "ENTER" : "EXIT";
                publisher.publish(
                        org,
                        new GeofenceEvent(
                                resp.deviceId(),
                                z.id(),
                                z.name(),
                                type,
                                loc.lat(),
                                loc.lng(),
                                resp.recordedAt()));
                (tr == ReachTransition.ENTER ? enterCount : exitCount).increment();
                log.info("지오펜스 {} — {} · {} ({})", type, resp.deviceId(), z.name(), z.id());
            }
            store.put(resp.deviceId(), z.id(), now);
        }
    }

    private TelemetryResponse tryRead(MapRecord<String, Object, Object> rec) {
        Object data = rec.getValue().get(StreamIngestWriter.PAYLOAD_FIELD);
        if (data == null) {
            return null;
        }
        try {
            return json.readValue(data.toString(), TelemetryResponse.class);
        } catch (Exception e) {
            log.warn("geofence payload 역직렬화 실패(id={}): {}", rec.getId(), e.toString());
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
