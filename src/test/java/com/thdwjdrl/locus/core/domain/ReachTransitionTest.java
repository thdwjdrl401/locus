package com.thdwjdrl.locus.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 도달 상태 전이 — 이전(null/안/밖) × 현재 → ENTER/EXIT/유지. */
class ReachTransitionTest {

    @Test
    void 미관측에서_안이면_ENTER() {
        assertThat(ReachTransition.of(null, true)).isEqualTo(ReachTransition.ENTER);
    }

    @Test
    void 미관측에서_밖이면_STAY_OUTSIDE() {
        assertThat(ReachTransition.of(null, false)).isEqualTo(ReachTransition.STAY_OUTSIDE);
    }

    @Test
    void 밖에서_안이면_ENTER() {
        assertThat(ReachTransition.of(false, true)).isEqualTo(ReachTransition.ENTER);
    }

    @Test
    void 안에서_밖이면_EXIT() {
        assertThat(ReachTransition.of(true, false)).isEqualTo(ReachTransition.EXIT);
    }

    @Test
    void 안_유지는_STAY_INSIDE() {
        assertThat(ReachTransition.of(true, true)).isEqualTo(ReachTransition.STAY_INSIDE);
    }

    @Test
    void 밖_유지는_STAY_OUTSIDE() {
        assertThat(ReachTransition.of(false, false)).isEqualTo(ReachTransition.STAY_OUTSIDE);
    }

    @Test
    void 크로싱_판정() {
        assertThat(ReachTransition.ENTER.isCrossing()).isTrue();
        assertThat(ReachTransition.EXIT.isCrossing()).isTrue();
        assertThat(ReachTransition.STAY_INSIDE.isCrossing()).isFalse();
        assertThat(ReachTransition.STAY_OUTSIDE.isCrossing()).isFalse();
    }
}
