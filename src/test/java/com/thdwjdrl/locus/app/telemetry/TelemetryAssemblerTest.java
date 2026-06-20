package com.thdwjdrl.locus.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.thdwjdrl.locus.app.telemetry.TelemetryRequest.BatteryDto;
import com.thdwjdrl.locus.app.telemetry.TelemetryRequest.LocationDto;
import com.thdwjdrl.locus.app.telemetry.TelemetryRequest.NetworkDto;
import com.thdwjdrl.locus.core.domain.ActivityType;
import com.thdwjdrl.locus.core.domain.AppState;
import com.thdwjdrl.locus.core.domain.DeviceType;
import com.thdwjdrl.locus.core.domain.NetworkType;
import com.thdwjdrl.locus.core.domain.PermissionState;
import com.thdwjdrl.locus.core.domain.Telemetry;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 조립 + 최소수집 게이트 검증 (순수 변환). */
class TelemetryAssemblerTest {

    private final TelemetryAssembler assembler = new TelemetryAssembler();
    private final Instant now = Instant.parse("2026-06-20T09:00:00Z");
    private final LocationDto loc = new LocationDto(37.0, 127.0, 5.0, null, 1.0, 90.0);

    private TelemetryRequest req(
            PermissionState permission, boolean sharing, LocationDto location) {
        return new TelemetryRequest(
                "phone-1",
                DeviceType.PHONE,
                now,
                location,
                new BatteryDto(80, false),
                new NetworkDto(NetworkType.CELLULAR, true),
                ActivityType.WALKING,
                AppState.FOREGROUND,
                permission,
                sharing);
    }

    @Test
    void 정상이면_위치를_저장한다() {
        Telemetry t = assembler.toTelemetry(req(PermissionState.WHILE_IN_USE, true, loc), now);
        assertThat(t.getLocation()).isNotNull();
        assertThat(t.getLocation().getLatitude()).isEqualTo(37.0);
        assertThat(t.getReceivedAt()).isEqualTo(now);
    }

    @Test
    void 공유off면_위치를_버린다() {
        Telemetry t = assembler.toTelemetry(req(PermissionState.WHILE_IN_USE, false, loc), now);
        assertThat(t.getLocation()).isNull();
    }

    @Test
    void 권한DENIED면_위치를_버린다() {
        Telemetry t = assembler.toTelemetry(req(PermissionState.DENIED, true, loc), now);
        assertThat(t.getLocation()).isNull();
    }

    @Test
    void 메트릭_맵에_battery_network가_담긴다() {
        Telemetry t = assembler.toTelemetry(req(PermissionState.WHILE_IN_USE, true, loc), now);
        assertThat(t.getMetrics())
                .containsKeys("battery", "network", "permission", "sharingEnabled");
    }
}
