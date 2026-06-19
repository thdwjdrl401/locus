/**
 *
 *
 * <h2>core.strategy — 양축 추상화의 전략 인터페이스</h2>
 *
 * 계획서 §3·§4의 "양축 추상화"가 사는 곳. 인터페이스만 둔다. 구현은 {@code app}.
 *
 * <h3>두 축</h3>
 *
 * <ul>
 *   <li><b>DeviceType 축(누가 수행)</b>: {@code DeviceTypeHandler}, {@code MovementProfile} — 구현 {@code
 *       PhoneHandler}/{@code PhoneProfile}는 {@code app.device}.
 *   <li><b>MissionType 축(무엇을 시키나)</b>: {@code MissionType} — 페이즈 1은 인터페이스만. 첫 구현 {@code
 *       SingleReachMission}은 M9.
 *   <li><b>판정</b>: {@code ReachEvaluator} — 지오펜스(M5)와 미션 도달(M9)이 공유.
 * </ul>
 *
 * <p>여기에 인터페이스를, {@code app}에 구현을 둠으로써 새 타입 추가 시 {@code core}가 안 바뀐다(M3·M9 증명 지점).
 */
package com.thdwjdrl.locus.core.strategy;
