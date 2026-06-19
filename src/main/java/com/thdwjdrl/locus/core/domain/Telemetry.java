package com.thdwjdrl.locus.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 텔레메트리 — 위치·상태 1프레임.
 *
 * <p>공통 컬럼(deviceId, deviceType, 시각, {@link Location}) + 타입별 {@code metrics} JSON 의 단일 테이블 (계획서
 * §4). 폰 전용 필드(battery/network/activity/appState/permission/sharingEnabled)는 {@code metrics}로 흡수한다.
 *
 * <p>멱등: {@code UNIQUE(device_id, recorded_at)} — "순진하게 먼저" DB unique. M2에서 Redis로 발전. Device와는 FK
 * 없이 {@code deviceId} 문자열로 느슨하게 연결한다(고빈도 단건 insert 비용 회피).
 *
 * <p>{@code location}은 nullable: 권한 거부·공유 off 시 미수집(최소 수집).
 */
@Entity
@Table(
        name = "telemetry",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_telemetry_device_time",
                        columnNames = {"device_id", "recorded_at"}),
        indexes = @Index(name = "idx_telemetry_device_time", columnList = "device_id, recorded_at"))
public class Telemetry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_type", nullable = false)
    private DeviceType deviceType;

    /** 단말이 측정한 시각(봉투의 timestamp). */
    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    /** 서버가 수신한 시각. */
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Embedded private Location location;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private Map<String, Object> metrics = new HashMap<>();

    protected Telemetry() {}

    public Telemetry(
            String deviceId,
            DeviceType deviceType,
            Instant recordedAt,
            Instant receivedAt,
            Location location,
            Map<String, Object> metrics) {
        this.deviceId = deviceId;
        this.deviceType = deviceType;
        this.recordedAt = recordedAt;
        this.receivedAt = receivedAt;
        this.location = location;
        this.metrics = (metrics != null) ? metrics : new HashMap<>();
    }

    public Long getId() {
        return id;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public DeviceType getDeviceType() {
        return deviceType;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Location getLocation() {
        return location;
    }

    public Map<String, Object> getMetrics() {
        return metrics;
    }
}
