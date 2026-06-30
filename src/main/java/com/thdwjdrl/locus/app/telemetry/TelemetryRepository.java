package com.thdwjdrl.locus.app.telemetry;

import com.thdwjdrl.locus.core.domain.Telemetry;
import com.thdwjdrl.locus.core.domain.TelemetryId;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * 텔레메트리 영속·조회.
 *
 * <p>복합 PK {@code (device_id, recorded_at)} — TimescaleDB 하이퍼테이블 파티션 키 포함 요건 충족. 직접 저장은 {@link
 * DirectIngestWriter}가 {@code EntityManager.persist()}로 수행(INSERT 강제, merge 회피).
 *
 * <p>조회는 모두 복합 PK 인덱스를 탄다. {@link #findLatestPerDevice()}는 naive 상관 서브쿼리 — 데이터가 커지면 느려질 수 있고, 그게 M4
 * {@code LatestStateLookup}(최신상태 캐시) 개선의 before 대상이다.
 */
public interface TelemetryRepository extends JpaRepository<Telemetry, TelemetryId> {

    /** 한 디바이스의 최신 1프레임. */
    Optional<Telemetry> findTopByDeviceIdOrderByRecordedAtDesc(String deviceId);

    /** 한 디바이스의 이력(최신순, offset 페이징 — 커서는 M7). */
    Page<Telemetry> findByDeviceIdOrderByRecordedAtDesc(String deviceId, Pageable pageable);

    /** 디바이스별 최신 1프레임(관제 지도의 "전체 최신"). naive 상관 서브쿼리 — M4 캐시 도입 전의 기준 구현. */
    @Query(
            "SELECT t FROM Telemetry t WHERE t.recordedAt = "
                    + "(SELECT MAX(t2.recordedAt) FROM Telemetry t2 WHERE t2.deviceId = t.deviceId)")
    List<Telemetry> findLatestPerDevice();
}
