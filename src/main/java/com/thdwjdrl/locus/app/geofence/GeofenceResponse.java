package com.thdwjdrl.locus.app.geofence;

/** 지오펜스 조회 응답 (프론트가 지도에 원을 그릴 때 사용). */
public record GeofenceResponse(
        String id,
        String org,
        String name,
        double centerLat,
        double centerLng,
        double radiusMeters) {

    static GeofenceResponse from(Geofence g) {
        return new GeofenceResponse(
                g.id(), g.orgId(), g.name(), g.centerLat(), g.centerLng(), g.radiusMeters());
    }
}
