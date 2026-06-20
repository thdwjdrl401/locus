package com.thdwjdrl.locus.app.telemetry;

import com.thdwjdrl.locus.core.domain.Telemetry;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 텔레메트리 영속.
 *
 * <p>M0는 단건 저장(순진하게 먼저). 멱등은 DB의 {@code UNIQUE(device_id, recorded_at)}로 — 중복 저장 시도 시 제약 위반. 벌크
 * 적재·멱등 체크 발전은 M2. 이력 커서 조회는 M7.
 */
public interface TelemetryRepository extends JpaRepository<Telemetry, Long> {}
