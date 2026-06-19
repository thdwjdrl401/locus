package com.thdwjdrl.locus.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * 위치 값 객체(VO).
 *
 * <p>JPA {@code @Embeddable} 이라 record가 아닌 클래스다(무인자 생성자 제약). 전송 DTO는 record로 둔다(컨벤션 §6). 범위
 * 검증(위경도·정확도·속도 등)은 수집 경계(DTO Bean Validation)에서 한다. 여기서는 보관만.
 */
@Embeddable
public class Location {

    @Column(name = "lat")
    private Double latitude; // -90 ~ 90

    @Column(name = "lng")
    private Double longitude; // -180 ~ 180

    @Column(name = "accuracy_m")
    private Double accuracy; // m, >= 0

    @Column(name = "altitude_m")
    private Double altitude; // optional

    @Column(name = "speed_mps")
    private Double speed; // m/s, >= 0

    @Column(name = "heading_deg")
    private Double heading; // 0 ~ 360, optional

    protected Location() {}

    public Location(
            Double latitude,
            Double longitude,
            Double accuracy,
            Double altitude,
            Double speed,
            Double heading) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracy = accuracy;
        this.altitude = altitude;
        this.speed = speed;
        this.heading = heading;
    }

    public Double getLatitude() {
        return latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public Double getAccuracy() {
        return accuracy;
    }

    public Double getAltitude() {
        return altitude;
    }

    public Double getSpeed() {
        return speed;
    }

    public Double getHeading() {
        return heading;
    }
}
