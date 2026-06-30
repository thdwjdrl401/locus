package com.thdwjdrl.locus.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 텔레메트리 — 위치·상태 1프레임.
 *
 * <p>공통 컬럼(deviceId, deviceType, 시각, {@link Location}) + 타입별 {@code metrics} JSONB 의 단일 테이블 (계획서
 * §4). 폰 전용 필드(battery/network/activity/appState/permission/sharingEnabled)는 {@code metrics}로 흡수한다.
 *
 * <p>멱등: 복합 PK {@code (device_id, recorded_at)} — TimescaleDB 하이퍼테이블 제약상 파티션 컬럼(recorded_at)이 PK에
 * 포함돼야 한다. 배치 DAO는 {@code ON CONFLICT DO NOTHING}으로 중복을 조용히 버린다.
 *
 * <p>{@code location}은 nullable: 권한 거부·공유 off 시 미수집(최소 수집).
 */
@IdClass(TelemetryId.class)
@Entity
@Table(name = "telemetry")
public class Telemetry {

    @Id
    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_type", nullable = false)
    private DeviceType deviceType;

    /** 단말이 측정한 시각(봉투의 timestamp). 복합 PK의 파티션 컬럼. */
    @Id
    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    /** 서버가 수신한 시각. */
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Embedded private Location location;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
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
