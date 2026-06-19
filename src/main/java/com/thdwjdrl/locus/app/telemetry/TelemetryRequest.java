package com.thdwjdrl.locus.app.telemetry;

import com.thdwjdrl.locus.app.support.ValidTimestamp;
import com.thdwjdrl.locus.core.domain.ActivityType;
import com.thdwjdrl.locus.core.domain.AppState;
import com.thdwjdrl.locus.core.domain.DeviceType;
import com.thdwjdrl.locus.core.domain.NetworkType;
import com.thdwjdrl.locus.core.domain.PermissionState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;

/**
 * 텔레메트리 수집 봉투 (M0: 폰 형태).
 *
 * <p>형식·범위·시각 정합성은 여기 Bean Validation으로 검증한다. 타입별 의미 검증(예: network=NONE인데 online=true 모순)은 {@code
 * DeviceTypeHandler}(전략, 4단계)에서. 선택 필드(altitude/activity/appState/location 등)는 누락 허용,
 * 필수(deviceId/deviceType/timestamp/permission/sharingEnabled)는 거부.
 *
 * <p>중첩 record로 봉투 응집. core의 {@code Location}(영속 VO)과 구분해 {@code LocationDto} 등으로 명명.
 */
public record TelemetryRequest(
        @NotBlank String deviceId,
        @NotNull DeviceType deviceType,
        @NotNull @ValidTimestamp Instant timestamp,
        @Valid LocationDto location,
        @Valid BatteryDto battery,
        @Valid NetworkDto network,
        ActivityType activity,
        AppState appState,
        @NotNull PermissionState permission,
        @NotNull Boolean sharingEnabled) {

    public record LocationDto(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
            @NotNull @PositiveOrZero Double accuracy,
            Double altitude,
            @PositiveOrZero Double speed,
            @DecimalMin("0") @DecimalMax("360") Double heading) {}

    public record BatteryDto(@NotNull @Min(0) @Max(100) Integer level, @NotNull Boolean charging) {}

    public record NetworkDto(@NotNull NetworkType type, @NotNull Boolean online) {}
}
