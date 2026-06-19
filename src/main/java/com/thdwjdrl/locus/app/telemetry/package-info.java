/**
 *
 *
 * <h2>app.telemetry — 텔레메트리 수집·조회 슬라이스</h2>
 *
 * <h3>담기는 것 (M0)</h3>
 *
 * <ul>
 *   <li>{@code TelemetryController} — {@code POST /api/telemetry}
 *   <li>{@code TelemetryRequest}/{@code TelemetryResponse} DTO + Bean Validation (범위 검증, 커스텀
 *       {@code @ValidTimestamp})
 *   <li>{@code TelemetryIngestService} — <b>M0는 순진하게 단건 insert</b>
 *   <li>{@code TelemetryRepository}
 * </ul>
 *
 * <h3>개선 이음새 (포트가 생기는 곳, 결정 0004)</h3>
 *
 * <ul>
 *   <li>M2: 수집 포트 — {@code DirectSaveIngest} → {@code KafkaPublishIngest}
 *   <li>M7: 조회 — offset 페이징 → 커서 페이징
 * </ul>
 */
package com.thdwjdrl.locus.app.telemetry;
