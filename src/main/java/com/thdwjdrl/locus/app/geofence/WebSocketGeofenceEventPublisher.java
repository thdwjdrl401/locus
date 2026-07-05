package com.thdwjdrl.locus.app.geofence;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 지오펜스 이벤트를 STOMP 토픽 {@code /topic/org/{orgId}/geofence}로 브로드캐스트. 관제 화면이 이 토픽을 구독해 이벤트 피드·원 펄스를
 * 표시한다({@code LiveUpdatePublisher}와 같은 방식, 다른 목적지).
 */
@Component
public class WebSocketGeofenceEventPublisher implements GeofenceEventPublisher {

    private final SimpMessagingTemplate messaging;

    public WebSocketGeofenceEventPublisher(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    @Override
    public void publish(String orgId, GeofenceEvent event) {
        messaging.convertAndSend("/topic/org/" + orgId + "/geofence", event);
    }
}
