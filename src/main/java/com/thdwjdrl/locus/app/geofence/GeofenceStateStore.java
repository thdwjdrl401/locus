package com.thdwjdrl.locus.app.geofence;

/**
 * per-(device, zone) 도달 상태 저장 포트 (ADR 0004 개선 이음새). ENTER/EXIT 전이 판정에 직전 상태가 필요하다. 슬라이스1={@link
 * InMemoryGeofenceStateStore}; 다중 인스턴스·재시작 내구가 필요해지면 Redis 구현으로 교체.
 */
public interface GeofenceStateStore {

    /** 직전 관측(안=true, 밖=false, 미관측=null). */
    Boolean inside(String deviceId, String geofenceId);

    void put(String deviceId, String geofenceId, boolean inside);
}
