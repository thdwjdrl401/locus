package com.thdwjdrl.locus.core.domain;

/**
 * 디바이스 타입.
 *
 * <p>닫힌 집합을 명시적으로 관리하는 타입 레지스트리다. 값 추가는 core 로직(engine·strategy·엔티티)을 건드리지 않는다 — 새 타입의 동작은 전부
 * {@code app}(Handler·Profile)에 있고 여기엔 이름만 는다(§2.2). {@code AMR}은 M3에서 추상화 검증을 위해 추가한 두 번째 타입.
 */
public enum DeviceType {
    PHONE,
    AMR
}
