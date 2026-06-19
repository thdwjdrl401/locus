/**
 *
 *
 * <h2>app.device — 디바이스 조회 + DeviceType 전략 구현</h2>
 *
 * <h3>담기는 것 (M0)</h3>
 *
 * <ul>
 *   <li>{@code DeviceController} — {@code GET /api/devices}, {@code GET /api/devices/{id}}
 *   <li>{@code DeviceQueryService}, {@code DeviceRepository}
 *   <li>{@code PhoneHandler} implements {@code core.strategy.DeviceTypeHandler}
 *   <li>{@code PhoneProfile} implements {@code core.strategy.MovementProfile}
 * </ul>
 *
 * <h3>추상화 증명 (M3)</h3>
 *
 * 새 디바이스 타입(예: GPS 태그)은 여기에 {@code TagHandler}/{@code TagProfile}로 추가된다. {@code core}는 한 줄도 안 바뀐다 →
 * PR diff로 입증.
 */
package com.thdwjdrl.locus.app.device;
