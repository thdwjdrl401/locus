package com.thdwjdrl.locus.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.thdwjdrl.locus.core.domain.Location;
import org.junit.jupiter.api.Test;

/** 원형 도달 판정(haversine) — 내부/경계/외부/널. */
class RadiusEvaluatorTest {

    private final RadiusEvaluator evaluator = new RadiusEvaluator();
    private static final double LAT = 37.5, LNG = 127.0;

    private Location at(double lat, double lng) {
        return new Location(lat, lng, 5.0, null, 0.0, null);
    }

    @Test
    void 중심은_내부() {
        assertThat(evaluator.isInside(at(LAT, LNG), LAT, LNG, 100.0)).isTrue();
    }

    @Test
    void 반경_안쪽은_내부() {
        // 약 50m 북쪽(1m ≈ 0.00000898 위도)
        assertThat(evaluator.isInside(at(LAT + 0.000449, LNG), LAT, LNG, 100.0)).isTrue();
    }

    @Test
    void 반경_바깥은_외부() {
        // 약 150m 북쪽
        assertThat(evaluator.isInside(at(LAT + 0.001347, LNG), LAT, LNG, 100.0)).isFalse();
    }

    @Test
    void 위치_null이면_외부() {
        assertThat(evaluator.isInside(null, LAT, LNG, 100.0)).isFalse();
        assertThat(
                        evaluator.isInside(
                                new Location(null, null, null, null, null, null), LAT, LNG, 100.0))
                .isFalse();
    }

    @Test
    void haversine_거리는_대략_맞다() {
        // 위도 1도 ≈ 111.2km
        double d = RadiusEvaluator.haversineMeters(37.0, 127.0, 38.0, 127.0);
        assertThat(d).isCloseTo(111_195.0, within(500.0));
    }
}
