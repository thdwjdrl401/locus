package com.thdwjdrl.locus.app.device;

import com.thdwjdrl.locus.core.domain.DeviceType;
import com.thdwjdrl.locus.core.domain.InvalidTelemetryException;
import com.thdwjdrl.locus.core.domain.NetworkType;
import com.thdwjdrl.locus.core.domain.Telemetry;
import com.thdwjdrl.locus.core.strategy.DeviceTypeHandler;
import org.springframework.stereotype.Component;

/**
 * 폰 타입 처리 전략 (DeviceType 축의 첫 구현).
 *
 * <p>형식·범위는 DTO가 이미 걸렀으므로 여기선 폰 도메인 규칙(메트릭 간 모순)을 본다. 새 타입(M3 태그 등)은 이와 같은 형태의 핸들러를 추가하면 되고 core는 안
 * 바뀐다.
 */
@Component
public class PhoneHandler implements DeviceTypeHandler {

    @Override
    public DeviceType deviceType() {
        return DeviceType.PHONE;
    }

    @Override
    public void validate(Telemetry telemetry) {
        PhoneMetrics m = PhoneMetrics.fromMetrics(telemetry.getMetrics());
        // 네트워크 없음(NONE)인데 온라인이라는 건 모순.
        if (m.networkType() == NetworkType.NONE && Boolean.TRUE.equals(m.online())) {
            throw new InvalidTelemetryException("network.type=NONE 이면서 online=true 일 수 없습니다");
        }
    }
}
