package com.thdwjdrl.locus.app.geofence;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 시드 지오펜스 설정 (application.yml {@code locus.geofence.seeded}). {@code IngestProperties} 패턴. 슬라이스1은
 * config로만 정의하고 DB에 넣지 않는다(마이그레이션 회피).
 */
@Component
@ConfigurationProperties(prefix = "locus.geofence")
public class GeofenceProperties {

    private List<SeededGeofence> seeded = new ArrayList<>();

    public List<SeededGeofence> getSeeded() {
        return seeded;
    }

    public void setSeeded(List<SeededGeofence> seeded) {
        this.seeded = seeded;
    }

    /** 한 시드 지오펜스. 생성자 바인딩(레코드). */
    public record SeededGeofence(
            String id,
            String org,
            String name,
            Double centerLat,
            Double centerLng,
            Double radiusMeters) {}
}
