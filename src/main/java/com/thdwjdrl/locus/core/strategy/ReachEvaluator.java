package com.thdwjdrl.locus.core.strategy;

import com.thdwjdrl.locus.core.domain.Location;

/**
 * 도달 판정 전략 — 한 위치가 원형 영역(중심+반경) 안에 있는지 판정한다.
 *
 * <p>양축 추상화의 판정 축(계획서 §4). 지오펜스(M5)와 미션 도달(M9)이 <b>같은 엔진</b>을 공유한다. 구현({@code RadiusEvaluator})은
 * {@code core.engine}. 이 인터페이스는 미션·디바이스 타입을 모르고, 상태 저장 위치도 모른다(순수 기하). ENTER/EXIT 상태 전이는 {@link
 * com.thdwjdrl.locus.core.domain.ReachTransition}, 이전 상태 주입은 app 포트가 맡는다.
 */
public interface ReachEvaluator {

    /**
     * {@code pos}가 중심 {@code (centerLat, centerLng)} · 반경 {@code radiusMeters}(m) 원 안에 있으면 true.
     *
     * @param pos 판정할 위치(위경도)
     * @param centerLat 원 중심 위도
     * @param centerLng 원 중심 경도
     * @param radiusMeters 반경(m)
     */
    boolean isInside(Location pos, double centerLat, double centerLng, double radiusMeters);
}
