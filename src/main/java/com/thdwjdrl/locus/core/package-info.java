/**
 *
 *
 * <h2>core — 불변 코어 (infra-free)</h2>
 *
 * 디바이스·미션이 갈아끼워져도 바뀌지 않아야 하는 순수 도메인·전략·엔진.
 *
 * <h3>왜 이렇게 나눴나 (결정 0002 · 0003)</h3>
 *
 * <ul>
 *   <li>멀티모듈 대신 <b>단일 모듈 + ArchUnit</b>으로 경계를 강제한다.
 *   <li>{@code core}는 Spring/Kafka/Redis/Web에 의존할 수 없다 → {@code architecture.CoreIsolationTest}가 빌드
 *       단에서 검증한다.
 *   <li>JPA/Validation 표준 애너테이션(jakarta.*)은 허용한다(결정 0003 메모): 매퍼 보일러플레이트를 피하기 위한 실용적 타협.
 * </ul>
 *
 * <h3>무엇을 증명하나</h3>
 *
 * M3(새 디바이스 타입 추가)·M9(미션이 지오펜스 엔진 재사용) 시 이 패키지의 {@code git diff}가 0줄임을 보여 추상화를 입증한다.
 */
package com.thdwjdrl.locus.core;
