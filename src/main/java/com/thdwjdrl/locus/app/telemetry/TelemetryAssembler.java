package com.thdwjdrl.locus.app.telemetry;

import com.thdwjdrl.locus.app.device.PhoneMetrics;
import com.thdwjdrl.locus.core.domain.Location;
import com.thdwjdrl.locus.core.domain.PermissionState;
import com.thdwjdrl.locus.core.domain.Telemetry;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 수집 봉투(DTO) → 도메인 {@link Telemetry} 조립. 순수 변환이라 단위테스트가 쉽다.
 *
 * <p><b>최소수집 게이트</b>: 권한 거부(DENIED) 또는 공유 off(sharingEnabled=false)면 위치를 버린다(저장 시 null). 봉투에
 * location이 와도 수집하지 않는다(M6에서 로그 마스킹 등으로 강화).
 *
 * <p>M0는 폰 봉투 형태. 메트릭 맵 스키마는 {@link PhoneMetrics}가 소유.
 */
@Component
public class TelemetryAssembler {

    public Telemetry toTelemetry(TelemetryRequest req, Instant receivedAt) {
        return new Telemetry(
                req.deviceId(),
                req.deviceType(),
                req.timestamp(),
                receivedAt,
                gateLocation(req),
                metrics(req));
    }

    /** 최소수집 게이트. */
    private Location gateLocation(TelemetryRequest req) {
        boolean shared =
                req.permission() != PermissionState.DENIED
                        && Boolean.TRUE.equals(req.sharingEnabled());
        if (!shared || req.location() == null) {
            return null;
        }
        var l = req.location();
        return new Location(l.lat(), l.lng(), l.accuracy(), l.altitude(), l.speed(), l.heading());
    }

    private Map<String, Object> metrics(TelemetryRequest req) {
        var battery = req.battery();
        var network = req.network();
        return new PhoneMetrics(
                        battery != null ? battery.level() : null,
                        battery != null ? battery.charging() : null,
                        network != null ? network.type() : null,
                        network != null ? network.online() : null,
                        req.activity(),
                        req.appState(),
                        req.permission(),
                        req.sharingEnabled())
                .toMetrics();
    }
}
