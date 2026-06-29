package com.thdwjdrl.locus.app.telemetry;

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

/**
 * 텔레메트리 수집 오케스트레이션.
 *
 * <p>흐름: 조립(최소수집 게이트 포함) → 타입별 전략 검증 → {@link TelemetryIngestPort}로 적재 위임. **검증까지는 동기**라 잘못된 봉투는
 * 여전히 즉시 거부(400)된다. "어떻게 적재하느냐"(단건 vs 큐+배치)는 포트 구현이 정한다(ADR 0004 이음새) — 이 클래스는 적재 전략을 모른다.
 */
@Service
public class TelemetryIngestService {

    private final TelemetryAssembler assembler;
    private final Map<DeviceType, DeviceTypeHandler> handlers;
    private final TelemetryIngestPort ingestPort;
    private final Clock clock;

    public TelemetryIngestService(
            TelemetryAssembler assembler,
            List<DeviceTypeHandler> handlers,
            TelemetryIngestPort ingestPort,
            Clock clock) {
        this.assembler = assembler;
        this.handlers =
                handlers.stream()
                        .collect(
                                Collectors.toMap(
                                        DeviceTypeHandler::deviceType, Function.identity()));
        this.ingestPort = ingestPort;
        this.clock = clock;
    }

    public void ingest(TelemetryRequest request) {
        Instant receivedAt = clock.instant();
        Telemetry telemetry = assembler.toTelemetry(request, receivedAt);

        DeviceTypeHandler handler = handlers.get(request.deviceType());
        if (handler == null) {
            throw new InvalidTelemetryException("지원하지 않는 deviceType: " + request.deviceType());
        }
        handler.validate(telemetry);

        ingestPort.submit(telemetry);
    }
}
