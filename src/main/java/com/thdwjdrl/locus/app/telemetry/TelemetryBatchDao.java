package com.thdwjdrl.locus.app.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thdwjdrl.locus.core.domain.DeviceStatus;
import com.thdwjdrl.locus.core.domain.Location;
import com.thdwjdrl.locus.core.domain.Telemetry;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배치 적재 DAO (M1 A2).
 *
 * <p>핵심: {@code JdbcTemplate.batchUpdate}로 **N행을 한 트랜잭션·한 멀티로우 INSERT**로 보낸다 → fsync 1회가 N행을 커버.
 * 멀티로우로 묶이려면 datasource URL에 {@code reWriteBatchedInserts=true} 필수(PostgreSQL).
 *
 * <ul>
 *   <li>telemetry: {@code ON CONFLICT (device_id, recorded_at) DO NOTHING} — 복합 PK 중복은 조용히 버린다(유실
 *       허용).
 *   <li>device: 배치 내 deviceId 중복 제거 후 {@code ON CONFLICT (device_id) DO UPDATE}로 last_seen/status
 *       갱신.
 * </ul>
 *
 * <p>jsonb 파라미터: SQL 레벨 캐스팅({@code CAST(? AS jsonb)}, {@code ?::jsonb})은 JDBC PreparedStatement와
 * 호환되지 않는다. {@code ps.setObject(n, json, Types.OTHER)}로 바인딩하면 PostgreSQL JDBC 드라이버가 텍스트를 컬럼
 * 타입(jsonb)에 맞게 변환한다.
 */
// 조건 없이 항상 빈: queue(TelemetryBatchWorker)·stream(StreamStorageConsumer) 둘 다 쓴다.
// 상태 없는 DAO라 direct 모드에서 생성돼도 무해(미사용).
@Component
public class TelemetryBatchDao {

    private static final String INSERT_TELEMETRY =
            "INSERT INTO telemetry "
                    + "(device_id, device_type, recorded_at, received_at, "
                    + " lat, lng, accuracy_m, altitude_m, speed_mps, heading_deg, metrics) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?) "
                    + "ON CONFLICT (device_id, recorded_at) DO NOTHING";

    // PostgreSQL EXCLUDED 행 별칭 — 삽입 시도값을 UPDATE SET에서 참조.
    // org_id는 INSERT에만 있고 DO UPDATE엔 없다: 조직 배정은 생성 시 1회이고, 이미 배정된 org를
    // 이후 수집이 되돌리면 안 된다(locus.ingest.default-org 참조).
    private static final String UPSERT_DEVICE =
            "INSERT INTO device (device_id, device_type, status, first_seen_at, last_seen_at,"
                    + " org_id) "
                    + "VALUES (?,?,?,?,?,?) "
                    + "ON CONFLICT (device_id) DO UPDATE SET "
                    + " last_seen_at = GREATEST(COALESCE(device.last_seen_at, EXCLUDED.last_seen_at),"
                    + " EXCLUDED.last_seen_at), "
                    + " status = EXCLUDED.status";

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
        jdbc.batchUpdate(
                INSERT_TELEMETRY,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        Telemetry t = batch.get(i);
                        Location loc = t.getLocation();
                        ps.setString(1, t.getDeviceId());
                        ps.setString(2, t.getDeviceType().name());
                        ps.setTimestamp(3, Timestamp.from(t.getRecordedAt()));
                        ps.setTimestamp(4, Timestamp.from(t.getReceivedAt()));
                        ps.setObject(5, loc != null ? loc.getLatitude() : null);
                        ps.setObject(6, loc != null ? loc.getLongitude() : null);
                        ps.setObject(7, loc != null ? loc.getAccuracy() : null);
                        ps.setObject(8, loc != null ? loc.getAltitude() : null);
                        ps.setObject(9, loc != null ? loc.getSpeed() : null);
                        ps.setObject(10, loc != null ? loc.getHeading() : null);
                        // jsonb: Types.OTHER로 바인딩 — 드라이버가 text를 jsonb로 위임
                        ps.setObject(11, toJson(t.getMetrics()), Types.OTHER);
                    }

                    @Override
                    public int getBatchSize() {
                        return batch.size();
                    }
                });
    }

    /**
     * 배치 안에서 디바이스별 가장 최근 receivedAt만 남겨 upsert(중복 UPDATE 낭비 제거).
     *
     * <p>{@code TreeMap}으로 device_id 정렬 → 모든 워커가 device 행 락을 <b>같은 순서</b>로 잡는다 → 순환 대기 불가 = 데드락 0.
     * (M2-par 다중 워커에서 device upsert가 서로 엇갈린 순서로 락을 잡아 `deadlock detected`로 배치가 버려지던 문제 수정.)
     */
    private void upsertDevices(List<Telemetry> batch) {
        Map<String, Telemetry> latestPerDevice = new TreeMap<>();
        for (Telemetry t : batch) {
            latestPerDevice.merge(
                    t.getDeviceId(),
                    t,
                    (a, b) -> b.getReceivedAt().isAfter(a.getReceivedAt()) ? b : a);
        }
        List<Object[]> rows = new ArrayList<>(latestPerDevice.size());
        for (Telemetry t : latestPerDevice.values()) {
            rows.add(
                    new Object[] {
                        t.getDeviceId(),
                        t.getDeviceType().name(),
                        DeviceStatus.ONLINE.name(),
                        Timestamp.from(t.getReceivedAt()), // first_seen_at (INSERT 시에만 의미)
                        Timestamp.from(t.getReceivedAt()), // last_seen_at
                        props.getDefaultOrg() // org_id (INSERT 시에만 — 기존 배정 보존)
                    });
        }
        jdbc.batchUpdate(UPSERT_DEVICE, rows);
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
