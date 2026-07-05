package com.thdwjdrl.locus.app.geofence;

import java.time.Instant;

/**
 * 지오펜스 진입/이탈 이벤트 (WebSocket push · 조회 payload).
 *
 * @param type {@code "ENTER"} 또는 {@code "EXIT"}
 */
public record GeofenceEvent(
        String deviceId,
        String geofenceId,
        String zoneName,
        String type,
        double lat,
        double lng,
        Instant at) {}
