package com.thdwjdrl.locus.app.telemetry;

/**
 * 실시간 갱신 push 이음새(ADR 0004 출력 포트). "한 디바이스의 최신을 그 조직 구독자에게 브로드캐스트."
 *
 * <p>소스 무관 설계: 호출자만 A(배치워커 인프로세스) → B(Redis Streams monitoring 컨슈머)로 바뀌고, 이 포트와 WebSocket 전달 층은
 * 그대로다. 구현: {@link WebSocketLiveUpdatePublisher}. 상세: docs/specs/M4-realtime-read-path.md.
 */
public interface LiveUpdatePublisher {

    /** 조직 구독자에게 한 디바이스의 최신 프레임을 push. */
    void publish(String orgId, TelemetryResponse latest);
}
