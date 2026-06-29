package com.thdwjdrl.locus.app.telemetry;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.thdwjdrl.locus.app.device.PhoneHandler;
import com.thdwjdrl.locus.app.telemetry.TelemetryRequest.BatteryDto;
import com.thdwjdrl.locus.app.telemetry.TelemetryRequest.LocationDto;
import com.thdwjdrl.locus.app.telemetry.TelemetryRequest.NetworkDto;
import com.thdwjdrl.locus.core.domain.ActivityType;
import com.thdwjdrl.locus.core.domain.AppState;
import com.thdwjdrl.locus.core.domain.DeviceType;
import com.thdwjdrl.locus.core.domain.InvalidTelemetryException;
import com.thdwjdrl.locus.core.domain.NetworkType;
import com.thdwjdrl.locus.core.domain.PermissionState;
import com.thdwjdrl.locus.core.domain.Telemetry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 수집 오케스트레이션: 조립 → 전략 검증 게이트 → 포트 위임.
 *
 * <p>적재(Device upsert·Telemetry 저장)는 {@link DirectIngestWriter}의 책임이므로 여기선 포트를 목으로 두고 "검증을 통과하면 위임,
 * 실패하면 위임 안 함"만 본다.
 */
@ExtendWith(MockitoExtension.class)
class TelemetryIngestServiceTest {

    @Mock private TelemetryIngestPort ingestPort;

    private final Instant now = Instant.parse("2026-06-20T09:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    private TelemetryIngestService service() {
        return new TelemetryIngestService(
                new TelemetryAssembler(), List.of(new PhoneHandler()), ingestPort, clock);
    }

    private TelemetryRequest request(NetworkType networkType, boolean online) {
        return new TelemetryRequest(
                "phone-1",
                DeviceType.PHONE,
                now,
                new LocationDto(37.0, 127.0, 5.0, null, 1.0, 90.0),
                new BatteryDto(80, false),
                new NetworkDto(networkType, online),
                ActivityType.WALKING,
                AppState.FOREGROUND,
                PermissionState.WHILE_IN_USE,
                true);
    }

    @Test
    void 검증을_통과하면_포트로_위임한다() {
        service().ingest(request(NetworkType.CELLULAR, true));

        verify(ingestPort).submit(any(Telemetry.class));
    }

    @Test
    void 전략_검증_실패면_위임하지_않는다() {
        assertThatThrownBy(() -> service().ingest(request(NetworkType.NONE, true)))
                .isInstanceOf(InvalidTelemetryException.class);

        verify(ingestPort, never()).submit(any());
    }
}
