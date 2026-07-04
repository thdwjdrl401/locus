package com.thdwjdrl.locus.app.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thdwjdrl.locus.core.domain.DeviceType;
import com.thdwjdrl.locus.core.domain.InvalidTelemetryException;
import com.thdwjdrl.locus.core.domain.Location;
import com.thdwjdrl.locus.core.domain.NetworkType;
import com.thdwjdrl.locus.core.domain.PermissionState;
import com.thdwjdrl.locus.core.domain.Telemetry;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 폰 타입: 최소수집 게이트 + 타입별 의미 검증. */
class PhoneHandlerTest {

    private final PhoneHandler handler = new PhoneHandler();
    private final Location raw = new Location(37.0, 127.0, 5.0, null, 1.0, 90.0);

    private Map<String, Object> metrics(NetworkType type, boolean online) {
        return new PhoneMetrics(
                        80, false, type, online, null, null, PermissionState.WHILE_IN_USE, true)
                .toMetrics();
    }

    private Telemetry telemetryWith(NetworkType type, boolean online) {
        return new Telemetry(
                "phone-1",
                DeviceType.PHONE,
                Instant.now(),
                Instant.now(),
                null,
                metrics(type, online));
    }

    @Test
    void 담당_타입은_PHONE() {
        assertThat(handler.deviceType()).isEqualTo(DeviceType.PHONE);
    }

    // --- 최소수집 게이트 (프라이버시 셀링포인트) ---

    @Test
    void 권한WHILE_IN_USE_공유on이면_위치를_유지한다() {
        Map<String, Object> m =
                new PhoneMetrics(
                                80,
                                false,
                                NetworkType.CELLULAR,
                                true,
                                null,
                                null,
                                PermissionState.WHILE_IN_USE,
                                true)
                        .toMetrics();
        assertThat(handler.gate(raw, m)).isSameAs(raw);
    }

    @Test
    void 권한DENIED면_위치를_버린다() {
        Map<String, Object> m =
                new PhoneMetrics(
                                80,
                                false,
                                NetworkType.CELLULAR,
                                true,
                                null,
                                null,
                                PermissionState.DENIED,
                                true)
                        .toMetrics();
        assertThat(handler.gate(raw, m)).isNull();
    }

    @Test
    void 공유off면_위치를_버린다() {
        Map<String, Object> m =
                new PhoneMetrics(
                                80,
                                false,
                                NetworkType.CELLULAR,
                                true,
                                null,
                                null,
                                PermissionState.WHILE_IN_USE,
                                false)
                        .toMetrics();
        assertThat(handler.gate(raw, m)).isNull();
    }

    @Test
    void 원본위치가_null이면_null이다() {
        Map<String, Object> m =
                new PhoneMetrics(
                                80,
                                false,
                                NetworkType.CELLULAR,
                                true,
                                null,
                                null,
                                PermissionState.WHILE_IN_USE,
                                true)
                        .toMetrics();
        assertThat(handler.gate(null, m)).isNull();
    }

    // --- 타입별 의미 검증 ---

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

    @Test
    void 배터리_범위초과는_거부() {
        Map<String, Object> m =
                new PhoneMetrics(
                                150,
                                false,
                                NetworkType.CELLULAR,
                                true,
                                null,
                                null,
                                PermissionState.WHILE_IN_USE,
                                true)
                        .toMetrics();
        Telemetry t =
                new Telemetry("phone-1", DeviceType.PHONE, Instant.now(), Instant.now(), null, m);
        assertThatThrownBy(() -> handler.validate(t)).isInstanceOf(InvalidTelemetryException.class);
    }

    @Test
    void permission_이나_sharing_없으면_거부() {
        // 프라이버시 게이트 입력이 없으면 최소수집 판단이 모호해지므로 거부(옛 @NotNull 계약 복원).
        Map<String, Object> m =
                new PhoneMetrics(80, false, NetworkType.CELLULAR, true, null, null, null, null)
                        .toMetrics();
        Telemetry t =
                new Telemetry("phone-1", DeviceType.PHONE, Instant.now(), Instant.now(), null, m);
        assertThatThrownBy(() -> handler.validate(t)).isInstanceOf(InvalidTelemetryException.class);
    }

    @Test
    void 잘못된_network_enum은_거부() {
        Map<String, Object> m = Map.of("network", Map.of("type", "SATELLITE", "online", true));
        Telemetry t =
                new Telemetry("phone-1", DeviceType.PHONE, Instant.now(), Instant.now(), null, m);
        assertThatThrownBy(() -> handler.validate(t)).isInstanceOf(InvalidTelemetryException.class);
    }
}
