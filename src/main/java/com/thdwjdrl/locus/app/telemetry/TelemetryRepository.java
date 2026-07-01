package com.thdwjdrl.locus.app.telemetry;

import com.thdwjdrl.locus.core.domain.Telemetry;
import com.thdwjdrl.locus.core.domain.TelemetryId;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 텔레메트리 영속·조회.
 *
 * <p>복합 PK {@code (device_id, recorded_at)} — TimescaleDB 하이퍼테이블 파티션 키 포함 요건 충족. 직접 저장은 {@link
 * DirectIngestWriter}가 {@code EntityManager.persist()}로 수행(INSERT 강제, merge 회피).
 *
 * <p>조회는 모두 복합 PK 인덱스를 탄다. {@link #findLatestPerDevice()}는 PostgreSQL {@code DISTINCT ON}으로 device별
 * 최신 1행을 뽑는다 — 이전 상관 서브쿼리는 행마다 재실행돼 총 행수에 비례했다(부하·EXPLAIN으로 확인).
 */
public interface TelemetryRepository extends JpaRepository<Telemetry, TelemetryId> {

    /** 한 디바이스의 최신 1프레임. */
    Optional<Telemetry> findTopByDeviceIdOrderByRecordedAtDesc(String deviceId);

    /** 한 디바이스의 이력(최신순, offset 페이징 — 커서는 M7). */
    Page<Telemetry> findByDeviceIdOrderByRecordedAtDesc(String deviceId, Pageable pageable);

    /**
     * 디바이스별 최신 1프레임(관제 지도). PostgreSQL DISTINCT ON — PK (device_id, recorded_at) 인덱스로 device별 최신
     * 1행.
     */
    @Query(
            value =
                    "SELECT DISTINCT ON (device_id) * FROM telemetry "
                            + "ORDER BY device_id, recorded_at DESC",
            nativeQuery = true)
    List<Telemetry> findLatestPerDevice();

    /** 한 조직의 디바이스별 최신(스코프 조회 · 캐시 fallback). device.org_id로 필터 후 DISTINCT ON. */
    @Query(
            value =
                    "SELECT DISTINCT ON (t.device_id) t.* FROM telemetry t "
                            + "JOIN device d ON d.device_id = t.device_id "
                            + "WHERE d.org_id = :orgId "
                            + "ORDER BY t.device_id, t.recorded_at DESC",
            nativeQuery = true)
    List<Telemetry> findLatestByOrg(@Param("orgId") String orgId);
}
