package com.thdwjdrl.locus.app.simulator;

import com.thdwjdrl.locus.app.device.PhoneMetrics;
import com.thdwjdrl.locus.app.telemetry.TelemetryRequest;
import com.thdwjdrl.locus.app.telemetry.TelemetryRequest.LocationDto;
import com.thdwjdrl.locus.core.domain.ActivityType;
import com.thdwjdrl.locus.core.domain.AppState;
import com.thdwjdrl.locus.core.domain.DeviceType;
import com.thdwjdrl.locus.core.domain.NetworkType;
import com.thdwjdrl.locus.core.domain.PermissionState;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * 폰 움직임/상태 생성 (DeviceType 축 첫 프로파일).
 *
 * <p>보행자 random walk(작은 위경도 이동), 배터리 점진 감소, 가끔 신호 끊김/재연결. 무상태(공유 빈)라 {@link ThreadLocalRandom} 으로
 * 스레드 안전. 상태는 디바이스별 {@link SimState}에 있다.
 *
 * <p>M3 봉투 일반화 이후 폰 상태는 {@code metrics} 맵으로 보낸다 — {@link PhoneMetrics#toMetrics()}가 스키마의 단일 소유처.
 */
@Component
public class PhoneProfile implements MovementProfile {

    @Override
    public DeviceType deviceType() {
        return DeviceType.PHONE;
    }

    @Override
    public TelemetryRequest step(SimState s) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        s.tick++;

        // 보행자 random walk (약 ±0.0001도 ≈ 수 m)
        s.lat = clamp(s.lat + (rnd.nextDouble() - 0.5) * 0.0002, -90, 90);
        s.lng = clamp(s.lng + (rnd.nextDouble() - 0.5) * 0.0002, -180, 180);

        // 배터리 점진 감소 (약 30프레임당 1%)
        if (s.tick % 30 == 0 && s.batteryLevel > 5) {
            s.batteryLevel--;
        }

        // 신호 끊김/재연결 (약 2% 확률로 전환)
        if (rnd.nextInt(100) < 2) {
            s.online = !s.online;
        }

        var metrics =
                new PhoneMetrics(
                                s.batteryLevel,
                                false,
                                s.online ? NetworkType.CELLULAR : NetworkType.NONE,
                                s.online,
                                ActivityType.WALKING,
                                AppState.FOREGROUND,
                                PermissionState.WHILE_IN_USE,
                                true)
                        .toMetrics();

        return new TelemetryRequest(
                s.deviceId,
                DeviceType.PHONE,
                Instant.now(),
                new LocationDto(
                        s.lat,
                        s.lng,
                        3.0 + rnd.nextDouble() * 10,
                        null,
                        rnd.nextDouble() * 2,
                        rnd.nextDouble() * 360),
                metrics);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
