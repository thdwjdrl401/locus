package com.thdwjdrl.locus.core.engine;

import com.thdwjdrl.locus.core.domain.Location;
import com.thdwjdrl.locus.core.strategy.ReachEvaluator;

/**
 * 원형 영역 도달 판정 — haversine 거리로 원 안/밖을 판정한다 ({@link ReachEvaluator} 구현, M5 지오펜스).
 *
 * <p>순수 자바({@code Math}만 사용) — Spring·Redis·Web 의존 0(ArchUnit {@code CoreIsolationTest}가 강제). 지오펜스
 * 이탈 판정과 미션 도달 판정이 이 엔진을 공유한다(M5 → M9). 상태 저장은 모른다.
 */
public class RadiusEvaluator implements ReachEvaluator {

    /** 지구 평균 반지름(m). haversine 근사에 충분. */
    private static final double EARTH_RADIUS_M = 6_371_000.0;

    @Override
    public boolean isInside(Location pos, double centerLat, double centerLng, double radiusMeters) {
        if (pos == null || pos.getLatitude() == null || pos.getLongitude() == null) {
            return false;
        }
        return haversineMeters(pos.getLatitude(), pos.getLongitude(), centerLat, centerLng)
                <= radiusMeters;
    }

    /** 두 위경도 사이의 대권 거리(m). */
    static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a =
                Math.sin(dLat / 2) * Math.sin(dLat / 2)
                        + Math.cos(Math.toRadians(lat1))
                                * Math.cos(Math.toRadians(lat2))
                                * Math.sin(dLng / 2)
                                * Math.sin(dLng / 2);
        return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
