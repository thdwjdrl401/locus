package com.thdwjdrl.locus.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.thdwjdrl.locus.app.telemetry.TelemetryRequest.LocationDto;
import com.thdwjdrl.locus.core.domain.DeviceType;
import com.thdwjdrl.locus.core.domain.Location;
import com.thdwjdrl.locus.core.domain.Telemetry;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 조립: DTO→Location 변환과 게이트가 정한 위치·metrics passthrough (순수 변환, 게이트는 핸들러 소유). */
class TelemetryAssemblerTest {

    private final TelemetryAssembler assembler = new TelemetryAssembler();
    private final Instant now = Instant.parse("2026-06-20T09:00:00Z");
    private final LocationDto loc = new LocationDto(37.0, 127.0, 5.0, null, 1.0, 90.0);

    private TelemetryRequest req(LocationDto location, Map<String, Object> metrics) {
        return new TelemetryRequest("phone-1", DeviceType.PHONE, now, location, metrics);
    }

    @Test
    void 위치_DTO를_core_Location으로_변환한다() {
        Location l = assembler.toLocation(loc);
        assertThat(l).isNotNull();
        assertThat(l.getLatitude()).isEqualTo(37.0);
        assertThat(l.getLongitude()).isEqualTo(127.0);
    }

    @Test
    void 위치가_null이면_null이다() {
        assertThat(assembler.toLocation(null)).isNull();
    }

    @Test
    void 게이트가_정한_위치를_그대로_담는다() {
        Location gated = assembler.toLocation(loc);
        Telemetry t = assembler.toTelemetry(req(loc, Map.of()), gated, now);
        assertThat(t.getLocation()).isSameAs(gated);
        assertThat(t.getReceivedAt()).isEqualTo(now);
    }

    @Test
    void 게이트가_위치를_버리면_null로_저장된다() {
        Telemetry t = assembler.toTelemetry(req(loc, Map.of()), null, now);
        assertThat(t.getLocation()).isNull();
    }

    @Test
    void metrics를_그대로_담는다() {
        Map<String, Object> metrics =
                Map.of("battery", Map.of("level", 80), "sharingEnabled", true);
        Telemetry t = assembler.toTelemetry(req(loc, metrics), null, now);
        assertThat(t.getMetrics()).containsKeys("battery", "sharingEnabled");
    }

    @Test
    void metrics가_null이면_빈_맵이다() {
        Telemetry t = assembler.toTelemetry(req(loc, null), null, now);
        assertThat(t.getMetrics()).isEmpty();
    }
}
