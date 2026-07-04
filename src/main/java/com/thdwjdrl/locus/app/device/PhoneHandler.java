package com.thdwjdrl.locus.app.device;

import com.thdwjdrl.locus.core.domain.DeviceType;
import com.thdwjdrl.locus.core.domain.InvalidTelemetryException;
import com.thdwjdrl.locus.core.domain.Location;
import com.thdwjdrl.locus.core.domain.NetworkType;
import com.thdwjdrl.locus.core.domain.Telemetry;
import com.thdwjdrl.locus.core.strategy.DeviceTypeHandler;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 폰 타입 처리 전략 (DeviceType 축의 첫 구현).
 *
 * <p>최소수집 게이트와 폰 metrics 검증을 소유한다. 형식·범위 중 공통칸(위치·시각)은 DTO가 이미 걸렀고, metrics 안의 폰 형식(battery
 * 0~100·network enum)과 도메인 모순(network=NONE인데 online=true)은 여기서 본다. 새 타입은 core 변경 없이 이런 핸들러를 추가한다.
 */
@Component
public class PhoneHandler implements DeviceTypeHandler {

    @Override
    public DeviceType deviceType() {
        return DeviceType.PHONE;
    }

    /**
     * 최소수집 게이트: 권한 거부(permission=DENIED)이거나 공유 off(sharingEnabled!=true)면 위치를 버린다(저장 null). 봉투에 위치가
     * 와도 수집하지 않는다(§3.5 프라이버시). metrics는 자유칸이라 permission은 문자열, sharingEnabled는 불리언으로 온다.
     */
    @Override
    public Location gate(Location raw, Map<String, Object> metrics) {
        if (raw == null) {
            return null;
        }
        Object permission = metrics == null ? null : metrics.get("permission");
        Object sharingEnabled = metrics == null ? null : metrics.get("sharingEnabled");
        boolean denied = permission != null && "DENIED".equals(permission.toString());
        boolean shared = !denied && Boolean.TRUE.equals(sharingEnabled);
        return shared ? raw : null;
    }

    @Override
    public void validate(Telemetry telemetry) {
        PhoneMetrics m;
        try {
            m = PhoneMetrics.fromMetrics(telemetry.getMetrics());
        } catch (RuntimeException e) {
            // @Valid에서 사라진 폰 형식 검증 보강: metrics 파싱 실패(잘못된 enum 등)를 도메인 거부로.
            throw new InvalidTelemetryException("폰 metrics 형식 오류: " + e.getMessage());
        }
        // 프라이버시 게이트 입력은 필수(옛 봉투의 @NotNull permission·sharingEnabled 복원). 없으면 게이트 판단이
        // 모호해져 최소수집이 느슨해지므로 거부한다(§3.5).
        if (m.permission() == null || m.sharingEnabled() == null) {
            throw new InvalidTelemetryException("폰은 permission·sharingEnabled 가 필수입니다(프라이버시 게이트)");
        }
        if (m.batteryLevel() != null && (m.batteryLevel() < 0 || m.batteryLevel() > 100)) {
            throw new InvalidTelemetryException(
                    "battery.level 은 0~100 이어야 합니다: " + m.batteryLevel());
        }
        // 네트워크 없음(NONE)인데 온라인이라는 건 모순.
        if (m.networkType() == NetworkType.NONE && Boolean.TRUE.equals(m.online())) {
            throw new InvalidTelemetryException("network.type=NONE 이면서 online=true 일 수 없습니다");
        }
    }
}
