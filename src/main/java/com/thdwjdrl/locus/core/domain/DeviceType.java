package com.thdwjdrl.locus.core.domain;

/**
 * 디바이스 타입.
 *
 * <p>M0는 {@code PHONE}만 구현한다. 로봇·드론·태그 등 새 타입을 코어 변경 없이 추가하는 메커니즘은 M3에서 결정한다 (docs/ROADMAP.md 보류된
 * 결정). 지금 미리 값을 채워 추상화를 투기하지 않는다.
 */
public enum DeviceType {
    PHONE
}
