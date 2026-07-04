package com.thdwjdrl.locus.app.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thdwjdrl.locus.core.domain.DeviceType;
import com.thdwjdrl.locus.core.domain.InvalidTelemetryException;
import com.thdwjdrl.locus.core.domain.Location;
import com.thdwjdrl.locus.core.domain.Telemetry;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** AMR 타입별 의미 검증 + 게이트 없음(위치 항상 유지). */
class AmrHandlerTest {

    private final AmrHandler handler = new AmrHandler();
    private final Location raw = new Location(37.5, 127.0, 0.1, null, 0.5, 90.0);

    private Telemetry telemetry(Map<String, Object> metrics) {
        return new Telemetry("amr-1", DeviceType.AMR, Instant.now(), Instant.now(), null, metrics);
    }

    private AmrMetrics driving(String estop, String mode, String batteryStatus) {
        return new AmrMetrics(
                72, batteryStatus, mode, true, estop, "OK", 10.0, 5.0, 1.57, "site-1");
    }

    @Test
    void 담당_타입은_AMR() {
        assertThat(handler.deviceType()).isEqualTo(DeviceType.AMR);
    }

    @Test
    void 게이트는_로봇_위치를_항상_유지한다() {
        assertThat(handler.gate(raw, Map.of())).isSameAs(raw);
    }

    @Test
    void 정상_주행은_통과() {
        assertThatNoException()
                .isThrownBy(
                        () ->
                                handler.validate(
                                        telemetry(
                                                driving("NOT_ESTOPPED", "AUTOMATIC", "DISCHARGING")
                                                        .toMetrics())));
    }

    @Test
    void estop인데_주행이면_거부() {
        assertThatThrownBy(
                        () ->
                                handler.validate(
                                        telemetry(
                                                driving("ESTOPPED", "AUTOMATIC", "DISCHARGING")
                                                        .toMetrics())))
                .isInstanceOf(InvalidTelemetryException.class);
    }

    @Test
    void 점검모드인데_주행이면_거부() {
        assertThatThrownBy(
                        () ->
                                handler.validate(
                                        telemetry(
                                                driving("NOT_ESTOPPED", "SERVICE", "DISCHARGING")
                                                        .toMetrics())))
                .isInstanceOf(InvalidTelemetryException.class);
    }

    @Test
    void 충전중인데_주행이면_거부() {
        assertThatThrownBy(
                        () ->
                                handler.validate(
                                        telemetry(
                                                driving("NOT_ESTOPPED", "AUTOMATIC", "CHARGING")
                                                        .toMetrics())))
                .isInstanceOf(InvalidTelemetryException.class);
    }
}
