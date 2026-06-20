package com.thdwjdrl.locus.app.device;

import com.thdwjdrl.locus.core.domain.Device;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 디바이스 영속.
 *
 * <p>{@code deviceId}(업무 키)로 조회/존재확인 — 수집 시 Device upsert(없으면 생성)에 쓰인다. 목록 조회는 {@code
 * JpaRepository}의 {@code findAll(Pageable)}(M0 offset 페이징).
 */
public interface DeviceRepository extends JpaRepository<Device, Long> {

    Optional<Device> findByDeviceId(String deviceId);

    boolean existsByDeviceId(String deviceId);
}
