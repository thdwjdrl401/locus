package com.thdwjdrl.locus.app.geofence;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 지오펜스 조회 (관제 화면이 지도에 원을 그릴 때). {@code TelemetryController} 패턴 — org 지정 시 스코프, 없으면 전체. */
@RestController
@RequestMapping("/api/geofences")
public class GeofenceController {

    private final GeofenceCatalog catalog;

    public GeofenceController(GeofenceCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public List<GeofenceResponse> list(@RequestParam(required = false) String org) {
        List<Geofence> zones = (org == null) ? catalog.all() : catalog.zonesForOrg(org);
        return zones.stream().map(GeofenceResponse::from).toList();
    }
}
