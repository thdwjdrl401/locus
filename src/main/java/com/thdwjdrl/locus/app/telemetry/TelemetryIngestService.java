package com.thdwjdrl.locus.app.telemetry;

import com.thdwjdrl.locus.app.device.DeviceRepository;
import com.thdwjdrl.locus.core.domain.Device;
import com.thdwjdrl.locus.core.domain.DeviceStatus;
import com.thdwjdrl.locus.core.domain.DeviceType;
import com.thdwjdrl.locus.core.domain.InvalidTelemetryException;
import com.thdwjdrl.locus.core.domain.Telemetry;
import com.thdwjdrl.locus.core.strategy.DeviceTypeHandler;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 텔레메트리 수집 (M0: 순진하게 단건 저장).
 *
 * <p>흐름: 조립(게이트 포함) → 타입별 전략 검증 → Device upsert(정합성 유지) → Telemetry 저장. 수집 경로에서 Device를 upsert하므로
 * FK 없이도 고아 telemetry가 안 생긴다(논점 2). 큐·벌크는 M2.
 */
@Service
public class TelemetryIngestService {

    private final TelemetryAssembler assembler;
    private final Map<DeviceType, DeviceTypeHandler> handlers;
    private final DeviceRepository deviceRepository;
    private final TelemetryRepository telemetryRepository;
    private final Clock clock;

    public TelemetryIngestService(
            TelemetryAssembler assembler,
            List<DeviceTypeHandler> handlers,
            DeviceRepository deviceRepository,
            TelemetryRepository telemetryRepository,
            Clock clock) {
        this.assembler = assembler;
        this.handlers =
                handlers.stream()
                        .collect(
                                Collectors.toMap(
                                        DeviceTypeHandler::deviceType, Function.identity()));
        this.deviceRepository = deviceRepository;
        this.telemetryRepository = telemetryRepository;
        this.clock = clock;
    }

    @Transactional
    public void ingest(TelemetryRequest request) {
        Instant receivedAt = clock.instant();
        Telemetry telemetry = assembler.toTelemetry(request, receivedAt);

        DeviceTypeHandler handler = handlers.get(request.deviceType());
        if (handler == null) {
            throw new InvalidTelemetryException("지원하지 않는 deviceType: " + request.deviceType());
        }
        handler.validate(telemetry);

        upsertDevice(request.deviceId(), request.deviceType(), receivedAt);
        telemetryRepository.save(telemetry);
    }

    private void upsertDevice(String deviceId, DeviceType deviceType, Instant now) {
        Device device =
                deviceRepository
                        .findByDeviceId(deviceId)
                        .orElseGet(
                                () -> {
                                    Device created = new Device(deviceId, deviceType);
                                    created.setFirstSeenAt(now);
                                    return created;
                                });
        device.setLastSeenAt(now);
        device.setStatus(DeviceStatus.ONLINE);
        deviceRepository.save(device);
    }
}
