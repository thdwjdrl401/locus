package com.thdwjdrl.locus.app.telemetry;

import com.thdwjdrl.locus.app.device.DeviceRepository;
import com.thdwjdrl.locus.core.domain.Device;
import com.thdwjdrl.locus.core.domain.DeviceStatus;
import com.thdwjdrl.locus.core.domain.Telemetry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 단건 직접 적재 (M1 A0 — 기본 모드).
 *
 * <p>요청 1건 = 트랜잭션 1개 = 커밋 1회 = fsync ≥1회. M0에서 잰 ~33 req/s 천장의 경로다. {@code locus.ingest.mode}가 없거나
 * {@code direct}면 이 구현이 선택된다(기존 동작 보존).
 *
 * <p>큐·배치는 {@link QueuedIngestWriter}(M1 A2). 이 둘이 ADR 0004의 before/after 구현.
 */
@Component
@ConditionalOnProperty(name = "locus.ingest.mode", havingValue = "direct", matchIfMissing = true)
public class DirectIngestWriter implements TelemetryIngestPort {

    private final DeviceRepository deviceRepository;
    private final TelemetryRepository telemetryRepository;

    public DirectIngestWriter(
            DeviceRepository deviceRepository, TelemetryRepository telemetryRepository) {
        this.deviceRepository = deviceRepository;
        this.telemetryRepository = telemetryRepository;
    }

    @Override
    @Transactional
    public void submit(Telemetry telemetry) {
        upsertDevice(telemetry);
        telemetryRepository.save(telemetry);
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
