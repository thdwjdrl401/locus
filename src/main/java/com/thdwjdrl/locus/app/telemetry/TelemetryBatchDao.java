package com.thdwjdrl.locus.app.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thdwjdrl.locus.core.domain.DeviceStatus;
import com.thdwjdrl.locus.core.domain.Location;
import com.thdwjdrl.locus.core.domain.Telemetry;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배치 적재 DAO (M1 A2).
 *
 * <p>핵심: {@code JdbcTemplate.batchUpdate}로 **N행을 한 트랜잭션·한 멀티로우 INSERT**로 보낸다 → fsync 1회가 N행을 커버.
 * (엔티티가 {@code @GeneratedValue(IDENTITY)}라 Hibernate {@code saveAll}은 배치가 안 됨 — 그래서 JdbcTemplate.)
 * 멀티로우로 묶이려면 datasource URL에 {@code rewriteBatchedStatements=true} 필수.
 *
 * <ul>
 *   <li>telemetry: {@code INSERT IGNORE} — {@code UNIQUE(device_id, recorded_at)} 중복은 버린다(유실 허용).
 *   <li>device: 배치 내 deviceId 중복 제거 후 {@code ON DUPLICATE KEY UPDATE}로 last_seen/status 갱신.
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "locus.ingest.mode", havingValue = "queue")
public class TelemetryBatchDao {

    private static final String INSERT_TELEMETRY =
            "INSERT IGNORE INTO telemetry "
                    + "(device_id, device_type, recorded_at, received_at, "
                    + " lat, lng, accuracy_m, altitude_m, speed_mps, heading_deg, metrics) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?)";

    // MySQL 8.0.19+ 행 별칭(AS new) — 구식 VALUES() 함수의 deprecation 회피.
    private static final String UPSERT_DEVICE =
            "INSERT INTO device (device_id, device_type, status, first_seen_at, last_seen_at) "
                    + "VALUES (?,?,?,?,?) AS new "
                    + "ON DUPLICATE KEY UPDATE "
                    + " last_seen_at = GREATEST(COALESCE(device.last_seen_at, new.last_seen_at),"
                    + " new.last_seen_at), "
                    + " status = new.status";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final IngestProperties props;

    public TelemetryBatchDao(JdbcTemplate jdbc, ObjectMapper objectMapper, IngestProperties props) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    /** 한 배치를 한 트랜잭션으로 적재(telemetry + 선택적 device upsert). */
    @Transactional
    public void persistBatch(List<Telemetry> batch) {
        insertTelemetry(batch);
        if (props.isDeviceUpsert()) {
            upsertDevices(batch);
        }
    }

    private void insertTelemetry(List<Telemetry> batch) {
        List<Object[]> rows = new ArrayList<>(batch.size());
        for (Telemetry t : batch) {
            Location loc = t.getLocation();
            rows.add(
                    new Object[] {
                        t.getDeviceId(),
                        t.getDeviceType().name(),
                        utc(t.getRecordedAt()),
                        utc(t.getReceivedAt()),
                        loc != null ? loc.getLatitude() : null,
                        loc != null ? loc.getLongitude() : null,
                        loc != null ? loc.getAccuracy() : null,
                        loc != null ? loc.getAltitude() : null,
                        loc != null ? loc.getSpeed() : null,
                        loc != null ? loc.getHeading() : null,
                        toJson(t.getMetrics())
                    });
        }
        jdbc.batchUpdate(INSERT_TELEMETRY, rows);
    }

    /** 배치 안에서 디바이스별 가장 최근 receivedAt만 남겨 upsert(중복 UPDATE 낭비 제거). */
    private void upsertDevices(List<Telemetry> batch) {
        Map<String, Telemetry> latestPerDevice = new LinkedHashMap<>();
        for (Telemetry t : batch) {
            latestPerDevice.merge(
                    t.getDeviceId(),
                    t,
                    (a, b) -> b.getReceivedAt().isAfter(a.getReceivedAt()) ? b : a);
        }
        List<Object[]> rows = new ArrayList<>(latestPerDevice.size());
        for (Telemetry t : latestPerDevice.values()) {
            LocalDateTime seen = utc(t.getReceivedAt());
            rows.add(
                    new Object[] {
                        t.getDeviceId(),
                        t.getDeviceType().name(),
                        DeviceStatus.ONLINE.name(),
                        seen, // first_seen_at (INSERT 시에만 의미, UPDATE 절에 없음)
                        seen // last_seen_at
                    });
        }
        jdbc.batchUpdate(UPSERT_DEVICE, rows);
    }

    /** Instant → UTC LocalDateTime: MySQL DATETIME에 tz 변환 없이 바인딩(저장 표현을 Hibernate와 일치시킴). */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private String toJson(Map<String, Object> metrics) {
        try {
            return objectMapper.writeValueAsString(metrics != null ? metrics : Map.of());
        } catch (JsonProcessingException e) {
            // 메트릭 직렬화 실패는 한 행의 문제 — 빈 JSON으로 적재(전체 배치를 죽이지 않음).
            return "{}";
        }
    }
}
