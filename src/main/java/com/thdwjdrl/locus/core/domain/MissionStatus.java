package com.thdwjdrl.locus.core.domain;

/**
 * 미션 상태 전이. ASSIGNED → IN_PROGRESS → CLEARED/FAILED.
 *
 * <p>페이즈 1에서는 정의만. 상태 전이 구현은 페이즈 2(M9).
 */
public enum MissionStatus {
    ASSIGNED,
    IN_PROGRESS,
    CLEARED,
    FAILED
}
