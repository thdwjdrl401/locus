package com.thdwjdrl.locus.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 디바이스 메타.
 *
 * <p>{@code deviceId}는 외부에서 들어오는 업무 키(예: "phone-0427"), {@code id}는 내부 대리 키. 타입별 추가 메타는 {@code
 * metadata} JSON으로 흡수한다(텔레메트리 {@code metrics}와 같은 패턴).
 */
@Entity
@Table(
        name = "device",
        uniqueConstraints =
                @UniqueConstraint(name = "uk_device_device_id", columnNames = "device_id"))
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_type", nullable = false)
    private DeviceType deviceType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeviceStatus status = DeviceStatus.UNKNOWN;

    @Column(name = "first_seen_at")
    private Instant firstSeenAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata = new HashMap<>();

    protected Device() {}

    public Device(String deviceId, DeviceType deviceType) {
        this.deviceId = deviceId;
        this.deviceType = deviceType;
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

    public DeviceStatus getStatus() {
        return status;
    }

    public void setStatus(DeviceStatus status) {
        this.status = status;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public void setFirstSeenAt(Instant firstSeenAt) {
        this.firstSeenAt = firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    // 업무 키(deviceId) 기반 동등성. deviceId는 생성 시 부여되어 안정적이고 non-null.
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Device other)) {
            return false;
        }
        return deviceId != null && deviceId.equals(other.deviceId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(deviceId);
    }
}
