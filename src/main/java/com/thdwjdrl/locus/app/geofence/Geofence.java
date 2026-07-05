package com.thdwjdrl.locus.app.geofence;

/**
 * 원형 지오펜스 영역 (app 도메인). 슬라이스1은 config 시드 → 인메모리 {@link GeofenceCatalog}. CRUD·DB 영속은 이후 슬라이스.
 *
 * @param id 영역 식별자
 * @param orgId 소속 조직(스코프 키)
 * @param name 표시 이름
 * @param centerLat 중심 위도
 * @param centerLng 중심 경도
 * @param radiusMeters 반경(m)
 */
public record Geofence(
        String id,
        String orgId,
        String name,
        double centerLat,
        double centerLng,
        double radiusMeters) {}
