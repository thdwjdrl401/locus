package com.thdwjdrl.locus.app.geofence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 시드 지오펜스를 org별로 인덱싱한 인메모리 카탈로그. 판정 컨슈머는 {@link #zonesForOrg}로 디바이스 org의 영역만 검사한다. 슬라이스1은 불변(시드
 * 고정); CRUD는 이후 슬라이스에서 여기에 쓰기를 더한다.
 */
@Component
public class GeofenceCatalog {

    private final Map<String, List<Geofence>> byOrg;
    private final List<Geofence> all;

    public GeofenceCatalog(GeofenceProperties props) {
        List<Geofence> zones = new ArrayList<>();
        for (GeofenceProperties.SeededGeofence s : props.getSeeded()) {
            zones.add(
                    new Geofence(
                            s.id(),
                            s.org(),
                            s.name(),
                            s.centerLat(),
                            s.centerLng(),
                            s.radiusMeters()));
        }
        this.all = List.copyOf(zones);
        Map<String, List<Geofence>> m = new HashMap<>();
        for (Geofence z : zones) {
            m.computeIfAbsent(z.orgId(), k -> new ArrayList<>()).add(z);
        }
        Map<String, List<Geofence>> immutable = new HashMap<>();
        m.forEach((k, v) -> immutable.put(k, List.copyOf(v)));
        this.byOrg = Map.copyOf(immutable);
    }

    /** {@code orgId}에 속한 영역(없으면 빈 목록). */
    public List<Geofence> zonesForOrg(String orgId) {
        return byOrg.getOrDefault(orgId, List.of());
    }

    /** 전체 영역(super-admin 조회용). */
    public List<Geofence> all() {
        return all;
    }
}
