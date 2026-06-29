package com.thdwjdrl.locus.app.telemetry;

import com.thdwjdrl.locus.core.domain.Telemetry;

/**
 * 수집 출력 포트 (ADR 0004 — 개선 이음새).
 *
 * <p>"검증된 텔레메트리를 어떻게 적재하느냐"의 구현을 갈아끼우는 자리. {@link TelemetryIngestService}는 조립·검증까지만 하고 적재는 이 포트에
 * 위임한다. before/after를 A/B로 측정하려고 둔다.
 *
 * <ul>
 *   <li>{@code DirectIngestWriter} — 요청당 단건 저장(M1 A0, 기본).
 *   <li>{@code QueuedIngestWriter} — 인메모리 큐 적재 후 워커가 배치 저장(M1 A2).
 *   <li>(이후) Redis Streams 발행 — fan-out 트리거 시(M4~, ADR 0007).
 * </ul>
 */
public interface TelemetryIngestPort {

    /**
     * 검증을 통과한 텔레메트리 1건을 적재 경로에 넘긴다.
     *
     * <p>구현에 따라 동기 저장(direct)일 수도, 큐에 넣고 즉시 반환(queue)일 수도 있다. queue 모드에서 큐가 가득 차면 유실 허용 정책에 따라
     * drop될 수 있다(텔레메트리는 droppable, CLAUDE §3.5).
     */
    void submit(Telemetry telemetry);
}
