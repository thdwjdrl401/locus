package com.thdwjdrl.locus.app.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thdwjdrl.locus.core.domain.DeviceType;
import com.thdwjdrl.locus.core.domain.InvalidTelemetryException;
import com.thdwjdrl.locus.core.domain.NetworkType;
import com.thdwjdrl.locus.core.domain.PermissionState;
import com.thdwjdrl.locus.core.domain.Telemetry;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 폰 타입별 의미 검증. */
class PhoneHandlerTest {

    private final PhoneHandler handler = new PhoneHandler();

    private Telemetry telemetryWith(NetworkType type, boolean online) {
        var metrics =
                new PhoneMetrics(
                                80,
                                false,
                                type,
                                online,
                                null,
                                null,
                                PermissionState.WHILE_IN_USE,
                                true)
                        .toMetrics();
        return new Telemetry(
                "phone-1", DeviceType.PHONE, Instant.now(), Instant.now(), null, metrics);
    }

    @Test
    void 담당_타입은_PHONE() {
        assertThat(handler.deviceType()).isEqualTo(DeviceType.PHONE);
    }

    @Test
    void 정상_네트워크는_통과() {
        assertThatNoException()
                .isThrownBy(() -> handler.validate(telemetryWith(NetworkType.CELLULAR, true)));
    }

    @Test
    void NONE인데_online이면_거부() {
        assertThatThrownBy(() -> handler.validate(telemetryWith(NetworkType.NONE, true)))
                .isInstanceOf(InvalidTelemetryException.class);
    }
}
