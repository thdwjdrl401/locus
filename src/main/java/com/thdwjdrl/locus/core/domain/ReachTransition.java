package com.thdwjdrl.locus.core.domain;

/**
 * 도달 상태 전이 — 이전 관측(inside 여부)과 현재 관측으로부터 ENTER/EXIT/유지를 도출하는 순수 상태기계.
 *
 * <p>지오펜스(M5)·미션 도달(M9) 공용. "저장 위치를 모른다"(계획서 §4 판정 엔진) — 이전 상태는 app 포트({@code GeofenceStateStore})가
 * 주입한다.
 */
public enum ReachTransition {
    ENTER,
    EXIT,
    STAY_INSIDE,
    STAY_OUTSIDE;

    /**
     * 이전/현재 inside 여부로 전이를 도출한다.
     *
     * @param prevInside 직전 관측(안=true, 밖=false, 미관측=null)
     * @param nowInside 현재 관측
     */
    public static ReachTransition of(Boolean prevInside, boolean nowInside) {
        boolean was = Boolean.TRUE.equals(prevInside); // null(미관측)은 밖으로 취급 → 첫 진입이 ENTER
        if (was == nowInside) {
            return nowInside ? STAY_INSIDE : STAY_OUTSIDE;
        }
        return nowInside ? ENTER : EXIT;
    }

    public boolean isCrossing() {
        return this == ENTER || this == EXIT;
    }
}
