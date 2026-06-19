/**
 *
 *
 * <h2>core.domain — 도메인 모델</h2>
 *
 * 위치·상태 1프레임({@code Telemetry}), 디바이스 메타({@code Device}), 값 객체({@code Location}), 미션 모델({@code
 * Mission}), 그리고 enum들.
 *
 * <h3>담기는 것 (계획서 M0 기준)</h3>
 *
 * <ul>
 *   <li>Entity: {@code Telemetry}(공통 컬럼 + {@code metrics} JSON), {@code Device}
 *   <li>VO(@Embeddable): {@code Location}(lat, lng, accuracy, altitude, speed, heading)
 *   <li>Enum: {@code DeviceType}, {@code NetworkType}, {@code ActivityType}, {@code
 *       PermissionState}, {@code DeviceStatus}
 *   <li>{@code Mission} — <b>필드만 정의, 동작 없음</b>. 구현은 페이즈 2(M9).
 * </ul>
 *
 * <p>Spring 의존 금지. JPA 매핑 애너테이션은 허용(결정 0003 메모).
 */
package com.thdwjdrl.locus.core.domain;
