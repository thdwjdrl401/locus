package com.thdwjdrl.locus.app.telemetry;

import com.thdwjdrl.locus.core.domain.Location;
import com.thdwjdrl.locus.core.domain.Telemetry;
import java.time.Instant;
import java.util.HashMap;
import org.springframework.stereotype.Component;

/**
 * 수집 봉투(DTO) → 도메인 {@link Telemetry} 조립. 순수 변환이라 단위테스트가 쉽다.
 *
 * <p>디바이스 무관(M3): 봉투는 공통칸 + {@code metrics} 자유칸이라 여기선 타입을 모른다. 최소수집 게이트·타입별 metrics 해석은 {@code
 * DeviceTypeHandler}(전략)가 소유하고, 이 클래스는 게이트가 정한 위치와 metrics를 그대로 담기만 한다.
 */
@Component
public class TelemetryAssembler {

    /** DTO 위치 → core Location. null이면 null(수집 안 함). */
    public Location toLocation(TelemetryRequest.LocationDto l) {
        if (l == null) {
            return null;
        }
        return new Location(l.lat(), l.lng(), l.accuracy(), l.altitude(), l.speed(), l.heading());
    }

    /** 게이트가 정한 위치와 봉투 metrics(없으면 빈 맵)를 담아 도메인 텔레메트리 조립. */
    public Telemetry toTelemetry(TelemetryRequest req, Location gatedLocation, Instant receivedAt) {
        return new Telemetry(
                req.deviceId(),
                req.deviceType(),
                req.timestamp(),
                receivedAt,
                gatedLocation,
                req.metrics() != null ? req.metrics() : new HashMap<>());
    }
}
