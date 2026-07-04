package com.thdwjdrl.locus.app.device;

import com.thdwjdrl.locus.core.domain.DeviceType;
import com.thdwjdrl.locus.core.domain.InvalidTelemetryException;
import com.thdwjdrl.locus.core.domain.Telemetry;
import com.thdwjdrl.locus.core.strategy.DeviceTypeHandler;
import org.springframework.stereotype.Component;

/**
 * AMR(자율이동로봇) 타입 처리 전략 (DeviceType 축의 두 번째 구현 — M3 추상화 검증).
 *
 * <p>core 변경 없이 app에 핸들러만 추가해 새 타입을 지원함을 보인다. 게이트는 오버라이드하지 않는다 — 로봇 위치는 개인정보가 아니라 항상 수집한다(폰의 최소수집
 * 게이트와 대비). 검증은 AMR 상태 어휘의 표준 의미 모순을 본다.
 */
@Component
public class AmrHandler implements DeviceTypeHandler {

    @Override
    public DeviceType deviceType() {
        return DeviceType.AMR;
    }

    @Override
    public void validate(Telemetry telemetry) {
        AmrMetrics m = AmrMetrics.fromMetrics(telemetry.getMetrics());
        boolean driving = Boolean.TRUE.equals(m.driving());
        if (driving && "ESTOPPED".equals(m.estopState())) {
            throw new InvalidTelemetryException("estopState=ESTOPPED 이면서 driving=true 일 수 없습니다");
        }
        if (driving && "SERVICE".equals(m.operatingMode())) {
            throw new InvalidTelemetryException("operatingMode=SERVICE 이면서 driving=true 일 수 없습니다");
        }
        if (driving && "CHARGING".equals(m.batteryStatus())) {
            throw new InvalidTelemetryException("batteryStatus=CHARGING 이면서 driving=true 일 수 없습니다");
        }
    }
}
