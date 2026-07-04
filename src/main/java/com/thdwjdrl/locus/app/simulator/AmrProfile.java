package com.thdwjdrl.locus.app.simulator;

import com.thdwjdrl.locus.app.device.AmrMetrics;
import com.thdwjdrl.locus.app.telemetry.TelemetryRequest;
import com.thdwjdrl.locus.app.telemetry.TelemetryRequest.LocationDto;
import com.thdwjdrl.locus.core.domain.DeviceType;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * AMR(자율이동로봇) 움직임/상태 생성 (DeviceType 축 두 번째 프로파일 — M3).
 *
 * <p>맵 위 사각 웨이포인트 경로를 odom(x,y,θ)로 진전시키고, 사이트 anchor(lat/lng) 기준으로 odom→위경도로 변환해 봉투의 공통 location을
 * 채운다(엣지/시뮬이 좌표를 변환하고 원본 odom은 metrics에 보존한다는 계약). 배터리가 낮으면 원점(충전소)으로 복귀·충전하고 완충되면 순찰을 재개한다. 무상태 공유
 * 빈이라 상태는 디바이스별 {@link SimState}에 있다.
 */
@Component
public class AmrProfile implements MovementProfile {

    /** 순찰 경로(맵 기준 m). 원점(0,0)이 충전소. */
    private static final double[][] WAYPOINTS = {{10, 0}, {10, 10}, {0, 10}, {0, 0}};

    private static final double CHARGER_X = 0;
    private static final double CHARGER_Y = 0;

    /** 프레임당 이동 거리(m). */
    private static final double STEP_M = 0.5;

    /** 목표 도달 판정 임계(m). */
    private static final double REACH_M = 0.5;

    private static final int LOW_BATTERY = 20;
    private static final double METERS_PER_DEG_LAT = 111_320.0;

    @Override
    public DeviceType deviceType() {
        return DeviceType.AMR;
    }

    @Override
    public TelemetryRequest step(SimState s) {
        s.tick++;

        boolean driving;
        if (s.charging) {
            driving = false;
            // 완충까지 충전(약 2%/프레임).
            s.batteryPercent = Math.min(100, s.batteryPercent + 2);
            if (s.batteryPercent >= 100) {
                s.charging = false; // 순찰 재개
            }
        } else if (s.batteryPercent <= LOW_BATTERY) {
            // 충전소(원점)로 복귀.
            boolean arrived = moveToward(s, CHARGER_X, CHARGER_Y);
            driving = !arrived;
            if (arrived) {
                s.charging = true;
            } else {
                drain(s);
            }
        } else {
            // 순찰: 현재 웨이포인트로 이동, 도달 시 다음으로.
            double[] wp = WAYPOINTS[s.waypointIndex];
            if (moveToward(s, wp[0], wp[1])) {
                s.waypointIndex = (s.waypointIndex + 1) % WAYPOINTS.length;
            }
            drain(s);
            driving = true;
        }

        String batteryStatus =
                s.charging ? "CHARGING" : (s.batteryPercent >= 100 ? "FULL" : "DISCHARGING");
        var metrics =
                new AmrMetrics(
                                s.batteryPercent,
                                batteryStatus,
                                "AUTOMATIC",
                                driving,
                                "NOT_ESTOPPED",
                                "OK",
                                s.odomX,
                                s.odomY,
                                s.odomTheta,
                                "site-1")
                        .toMetrics();

        double lat = s.lat + s.odomY / METERS_PER_DEG_LAT;
        double lng = s.lng + s.odomX / (METERS_PER_DEG_LAT * Math.cos(Math.toRadians(s.lat)));
        double headingDeg = (Math.toDegrees(s.odomTheta) + 360) % 360;

        return new TelemetryRequest(
                s.deviceId,
                DeviceType.AMR,
                Instant.now(),
                new LocationDto(
                        clamp(lat, -90, 90),
                        clamp(lng, -180, 180),
                        0.1,
                        null,
                        driving ? STEP_M : 0.0,
                        headingDeg),
                metrics);
    }

    /** 목표(tx,ty)로 STEP_M만큼 이동. 도달하면 true. */
    private static boolean moveToward(SimState s, double tx, double ty) {
        double dx = tx - s.odomX;
        double dy = ty - s.odomY;
        double dist = Math.hypot(dx, dy);
        if (dist <= REACH_M) {
            s.odomX = tx;
            s.odomY = ty;
            return true;
        }
        s.odomTheta = Math.atan2(dy, dx);
        s.odomX += dx / dist * STEP_M;
        s.odomY += dy / dist * STEP_M;
        return false;
    }

    /** 주행 배터리 소모(약 10프레임당 1%). */
    private static void drain(SimState s) {
        if (s.tick % 10 == 0 && s.batteryPercent > 0) {
            s.batteryPercent--;
        }
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
