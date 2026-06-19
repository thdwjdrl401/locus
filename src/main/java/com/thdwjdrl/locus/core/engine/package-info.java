/**
 *
 *
 * <h2>core.engine — 명령·이동·판정 엔진</h2>
 *
 * 계획서 §4 가운데 박스 "[명령·이동·판정 엔진]". 견고하게 만들고 자주 안 바꾼다.
 *
 * <h3>담기는 것</h3>
 *
 * <ul>
 *   <li>{@code RadiusEvaluator} — haversine 거리로 ENTER/EXIT/INSIDE/OUTSIDE 판정 ({@code
 *       ReachEvaluator} 구현). M5 지오펜스에서 도입.
 *   <li>도달/이탈 상태 전이 로직 — 순수 자바. 어디 저장하는지(in-memory/Redis)는 모른다.
 * </ul>
 *
 * <h3>재사용 원칙 (M5 → M9)</h3>
 *
 * 지오펜스 이탈 판정과 미션 도달 판정이 <b>같은 엔진</b>을 쓴다. 엔진은 미션을 모른 채 동작한다(의존성 방향이 안쪽). 상태 저장은 출력 포트({@code
 * GeofenceStateStore}, 결정 0004)로 분리한다.
 */
package com.thdwjdrl.locus.core.engine;
