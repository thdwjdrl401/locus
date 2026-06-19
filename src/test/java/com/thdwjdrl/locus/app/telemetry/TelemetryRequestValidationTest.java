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
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** M0 검증 규칙: 범위·필수·시각 정합성의 거부/허용을 프로그램적 Validator로 확인. */
class TelemetryRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static <T> Set<ConstraintViolation<T>> validate(T target) {
        return validator.validate(target);
    }

    private TelemetryRequest valid() {
        return new TelemetryRequest(
                "phone-0001",
                DeviceType.PHONE,
                Instant.now(),
                new LocationDto(37.45, 126.70, 8.0, null, 1.2, 270.0),
                new BatteryDto(82, false),
                new NetworkDto(NetworkType.CELLULAR, true),
                ActivityType.WALKING,
                AppState.FOREGROUND,
                PermissionState.WHILE_IN_USE,
                true);
    }

    @Test
    void 정상_요청은_위반이_없다() {
        assertThat(validate(valid())).isEmpty();
    }

    @Test
    void 선택필드_누락은_허용된다() {
        var req =
                new TelemetryRequest(
                        "phone-0001",
                        DeviceType.PHONE,
                        Instant.now(),
                        null, // location 없음 (최소수집 등)
                        null, // battery 없음
                        null, // network 없음
                        null, // activity 없음
                        null, // appState 없음
                        PermissionState.DENIED,
                        false);
        assertThat(validate(req)).isEmpty();
    }

    @Test
    void deviceId_누락은_거부된다() {
        var req = copyWithDeviceId(null);
        assertThat(validate(req)).isNotEmpty();
    }

    @Test
    void 위도_범위초과는_거부된다() {
        var req = copyWithLocation(new LocationDto(100.0, 126.70, 8.0, null, 1.2, 270.0));
        assertThat(validate(req)).isNotEmpty();
    }

    @Test
    void 경도_범위초과는_거부된다() {
        var req = copyWithLocation(new LocationDto(37.45, 200.0, 8.0, null, 1.2, 270.0));
        assertThat(validate(req)).isNotEmpty();
    }

    @Test
    void 음수_정확도는_거부된다() {
        var req = copyWithLocation(new LocationDto(37.45, 126.70, -1.0, null, 1.2, 270.0));
        assertThat(validate(req)).isNotEmpty();
    }

    @Test
    void 배터리_범위초과는_거부된다() {
        var req =
                new TelemetryRequest(
                        "phone-0001",
                        DeviceType.PHONE,
                        Instant.now(),
                        new LocationDto(37.45, 126.70, 8.0, null, 1.2, 270.0),
                        new BatteryDto(150, false),
                        new NetworkDto(NetworkType.CELLULAR, true),
                        ActivityType.WALKING,
                        AppState.FOREGROUND,
                        PermissionState.WHILE_IN_USE,
                        true);
        assertThat(validate(req)).isNotEmpty();
    }

    @Test
    void 미래_타임스탬프는_거부된다() {
        var req = copyWithTimestamp(Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(validate(req)).isNotEmpty();
    }

    @Test
    void 과도한_과거_타임스탬프는_거부된다() {
        var req = copyWithTimestamp(Instant.now().minus(30, ChronoUnit.DAYS));
        assertThat(validate(req)).isNotEmpty();
    }

    // --- 헬퍼: 한 필드만 바꾼 변형 ---

    private TelemetryRequest copyWithDeviceId(String deviceId) {
        var v = valid();
        return new TelemetryRequest(
                deviceId,
                v.deviceType(),
                v.timestamp(),
                v.location(),
                v.battery(),
                v.network(),
                v.activity(),
                v.appState(),
                v.permission(),
                v.sharingEnabled());
    }

    private TelemetryRequest copyWithLocation(LocationDto location) {
        var v = valid();
        return new TelemetryRequest(
                v.deviceId(),
                v.deviceType(),
                v.timestamp(),
                location,
                v.battery(),
                v.network(),
                v.activity(),
                v.appState(),
                v.permission(),
                v.sharingEnabled());
    }

    private TelemetryRequest copyWithTimestamp(Instant timestamp) {
        var v = valid();
        return new TelemetryRequest(
                v.deviceId(),
                v.deviceType(),
                timestamp,
                v.location(),
                v.battery(),
                v.network(),
                v.activity(),
                v.appState(),
                v.permission(),
                v.sharingEnabled());
    }
}
