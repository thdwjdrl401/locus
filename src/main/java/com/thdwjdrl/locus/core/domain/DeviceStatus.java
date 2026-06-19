package com.thdwjdrl.locus.core.domain;

/**
 * 디바이스 연결/활동 상태(서버 판단).
 *
 * <p>최근 텔레메트리 수신 여부로 갱신한다. 판정 규칙(예: lastSeen 임계)은 이후 마일스톤에서 정한다.
 */
public enum DeviceStatus {
    ONLINE,
    OFFLINE,
    UNKNOWN
}
