package com.thdwjdrl.locus.app.telemetry;

import com.thdwjdrl.locus.app.device.DeviceRepository;
import com.thdwjdrl.locus.core.domain.Device;
import com.thdwjdrl.locus.core.domain.DeviceStatus;
import com.thdwjdrl.locus.core.domain.Telemetry;
import jakarta.persistence.EntityManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 단건 직접 적재 (M1 A0 — 기본 모드).
 *
 * <p>요청 1건 = 트랜잭션 1개 = 커밋 1회 = fsync ≥1회. M0에서 잰 ~33 req/s 최대 처리량의 경로다. {@code locus.ingest.mode}가
 * 없거나 {@code direct}면 이 구현이 선택된다(기존 동작 보존).
 *
 * <p>큐·배치는 {@link QueuedIngestWriter}(M1 A2). 이 둘이 ADR 0004의 before/after 구현.
 *
 * <p>telemetry 저장은 {@code entityManager.persist()}로 직접 INSERT. 복합 PK(device_id, recorded_at)는 항상
 * non-null이라 Spring Data JPA {@code save()}가 {@code merge()}를 택해 불필요한 SELECT를 유발하므로, persist로 강제해
 * INSERT 의미론과 중복 시 {@code DataIntegrityViolationException}(→409)을 보존한다.
 */
@Component
@ConditionalOnProperty(name = "locus.ingest.mode", havingValue = "direct", matchIfMissing = true)
public class DirectIngestWriter implements TelemetryIngestPort {

    private final DeviceRepository deviceRepository;
    private final EntityManager entityManager;

    public DirectIngestWriter(DeviceRepository deviceRepository, EntityManager entityManager) {
        this.deviceRepository = deviceRepository;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public void submit(Telemetry telemetry) {
        upsertDevice(telemetry);
        entityManager.persist(telemetry);
    }

    /** 수집 경로에서 Device upsert(없으면 생성) — FK 없이도 고아 telemetry가 안 생기게. */
    private void upsertDevice(Telemetry telemetry) {
        Device device =
                deviceRepository
                        .findByDeviceId(telemetry.getDeviceId())
                        .orElseGet(
                                () -> {
                                    Device created =
                                            new Device(
                                                    telemetry.getDeviceId(),
                                                    telemetry.getDeviceType());
                                    created.setFirstSeenAt(telemetry.getReceivedAt());
                                    return created;
                                });
        device.setLastSeenAt(telemetry.getReceivedAt());
        device.setStatus(DeviceStatus.ONLINE);
        deviceRepository.save(device);
    }
}
