package com.thdwjdrl.locus.app.geofence;

/**
 * 지오펜스 이벤트 push 포트 (ADR 0004). 구현={@link WebSocketGeofenceEventPublisher}. 소스 무관 — 판정 컨슈머는 이 포트만 알고
 * 전달 계층(WebSocket)을 모른다.
 */
public interface GeofenceEventPublisher {

    /** 조직 구독자에게 지오펜스 이벤트를 push. */
    void publish(String orgId, GeofenceEvent event);
}
