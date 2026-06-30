package com.thdwjdrl.locus.core.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 텔레메트리 복합 PK — {@code device_id + recorded_at}.
 *
 * <p>{@link Telemetry}의 {@code @IdClass}. {@code @Id} 필드명과 타입이 정확히 일치해야 한다. jakarta.persistence 표준만
 * 사용하므로 core 격리 규칙(ADR 0002) 준수.
 */
public class TelemetryId implements Serializable {

    private String deviceId;
    private Instant recordedAt;

    public TelemetryId() {}

    public TelemetryId(String deviceId, Instant recordedAt) {
        this.deviceId = deviceId;
        this.recordedAt = recordedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TelemetryId other)) {
            return false;
        }
        return Objects.equals(deviceId, other.deviceId)
                && Objects.equals(recordedAt, other.recordedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deviceId, recordedAt);
    }
}
