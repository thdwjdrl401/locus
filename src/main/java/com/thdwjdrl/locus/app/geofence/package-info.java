/**
 *
 *
 * <h2>app.geofence — 도달/이탈 판정 슬라이스 (M5)</h2>
 *
 * 텔레메트리 Stream의 세 번째 컨슈머 그룹({@code geofence}, ADR 0007)이 디바이스 위치를 원형 지오펜스에 대해 판정해 ENTER/EXIT 이벤트를
 * 낸다. 판정 기하·상태 전이는 {@code core}(순수), 배선·상태 저장은 여기.
 *
 * <h3>구성</h3>
 *
 * <ul>
 *   <li>{@link com.thdwjdrl.locus.app.geofence.StreamGeofenceConsumer} — geofence CG(단일 워커, poison
 *       내성). {@code core.engine.RadiusEvaluator} + {@code core.domain.ReachTransition}로 판정.
 *   <li>{@link com.thdwjdrl.locus.app.geofence.GeofenceCatalog} — config 시드({@link
 *       com.thdwjdrl.locus.app.geofence.GeofenceProperties})를 org별 인덱싱. 슬라이스1은 DB 없음.
 *   <li>{@link com.thdwjdrl.locus.app.geofence.GeofenceStateStore} — 상태 포트(ADR 0004). 인메모리 →
 *       Redis(이후).
 *   <li>{@link com.thdwjdrl.locus.app.geofence.GeofenceEventPublisher} — WebSocket push 포트.
 *   <li>{@link com.thdwjdrl.locus.app.geofence.GeofenceController} — {@code GET /api/geofences} 조회.
 * </ul>
 *
 * <h3>재사용 (M5 → M9)</h3>
 *
 * 판정 엔진({@code ReachEvaluator})은 미션 도달 판정과 공유. CRUD·DB 영속·폴리곤·Redis 상태·처리량 측정은 이후 슬라이스.
 */
package com.thdwjdrl.locus.app.geofence;
