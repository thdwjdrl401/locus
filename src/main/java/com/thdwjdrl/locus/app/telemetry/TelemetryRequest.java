package com.thdwjdrl.locus.app.telemetry;

import com.thdwjdrl.locus.app.support.ValidTimestamp;
import com.thdwjdrl.locus.core.domain.DeviceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.Map;

/**
 * 텔레메트리 수집 봉투 — 디바이스 무관 공통 형태 (M3 일반화).
 *
 * <p>공통칸(고정)은 {@code deviceId, deviceType, timestamp, location}뿐이다. 타입별 상태는 {@code metrics}(자유칸,
 * JSONB로 저장)로 넘긴다 — 폰은 battery/network/activity/appState/permission/sharingEnabled, AMR은
 * operatingMode/estopState/odom 등. 공통칸의 형식·범위·시각 정합성만 여기 Bean Validation으로 검증한다.
 *
 * <p>{@code metrics} 안의 형식·범위·모순 검증과 최소수집 게이트는 타입별 {@code DeviceTypeHandler}(전략)가 소유한다. 폰 metrics
 * 스키마는 {@code PhoneMetrics}, AMR은 {@code AmrMetrics}가 맵↔타입 변환을 담당한다.
 */
public record TelemetryRequest(
        @NotBlank String deviceId,
        @NotNull DeviceType deviceType,
        @NotNull @ValidTimestamp Instant timestamp,
        @Valid LocationDto location,
        Map<String, Object> metrics) {

    public record LocationDto(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
            @NotNull @PositiveOrZero Double accuracy,
            Double altitude,
            @PositiveOrZero Double speed,
            @DecimalMin("0") @DecimalMax("360") Double heading) {}
}
