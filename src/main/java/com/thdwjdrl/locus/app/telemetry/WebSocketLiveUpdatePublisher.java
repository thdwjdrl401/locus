package com.thdwjdrl.locus.app.telemetry;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link LiveUpdatePublisher}를 STOMP 토픽 {@code /topic/org/{orgId}}으로 구현.
 *
 * <p>지도 클라이언트는 자기 조직 토픽을 구독하고, 여기서 보낸 {@link TelemetryResponse}를 받아 마커를 갱신한다.
 */
@Component
public class WebSocketLiveUpdatePublisher implements LiveUpdatePublisher {

    private final SimpMessagingTemplate messaging;

    public WebSocketLiveUpdatePublisher(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    @Override
    public void publish(String orgId, TelemetryResponse latest) {
        messaging.convertAndSend("/topic/org/" + orgId, latest);
    }
}
