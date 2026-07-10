package com.thdwjdrl.locus.app.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 *   <li>device: 배치 내 deviceId 중복 제거 후 {@code ON CONFLICT (device_id) DO NOTHING}로 insert-if-absent
 *       (레지스트리). 라이브 상태(last_seen·status)는 여기서 안 건드린다 — M4 최신상태 프로젝션이 소유.
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

    // insert-if-absent: 처음 본 device만 레지스트리에 넣는다. status→DB 기본('UNKNOWN'), last_seen→null.
    private static final String REGISTER_DEVICE =
            "INSERT INTO device (device_id, device_type, first_seen_at) "
                    + "VALUES (?,?,?) "
                    + "ON CONFLICT (device_id) DO NOTHING";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final IngestProperties props;

    public TelemetryBatchDao(JdbcTemplate jdbc, ObjectMapper objectMapper, IngestProperties props) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    /** 한 배치를 한 트랜잭션으로 적재(telemetry + 선택적 device 레지스트리 등록). */
    @Transactional
    public void persistBatch(List<Telemetry> batch) {
        insertTelemetry(batch);
        if (props.isDeviceUpsert()) {
            registerDevices(batch);
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
     * 배치 안에서 device_id별 하나만 남겨 insert-if-absent(중복 INSERT 시도 축소).
     *
     * <p>{@code DO NOTHING}이라 UPDATE 행 락을 잡지 않아 워커 간 device 데드락은 구조상 사라졌다(옛 {@code DO UPDATE}가 엇갈린
     * 락 순서로 `deadlock detected`를 내던 문제도 함께 소멸). {@code TreeMap} dedup은 배치 내 같은 device의 중복 INSERT
     * 시도를 줄이려 유지한다.
     */
    private void registerDevices(List<Telemetry> batch) {
        Map<String, Telemetry> onePerDevice = new TreeMap<>();
        for (Telemetry t : batch) {
            onePerDevice.putIfAbsent(t.getDeviceId(), t);
        }
        List<Object[]> rows = new ArrayList<>(onePerDevice.size());
        for (Telemetry t : onePerDevice.values()) {
            rows.add(
                    new Object[] {
                        t.getDeviceId(),
                        t.getDeviceType().name(),
                        Timestamp.from(t.getReceivedAt()) // first_seen_at (INSERT 시에만 의미)
                    });
        }
        jdbc.batchUpdate(REGISTER_DEVICE, rows);
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
