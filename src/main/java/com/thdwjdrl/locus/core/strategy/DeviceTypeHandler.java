package com.thdwjdrl.locus.core.strategy;

import com.thdwjdrl.locus.core.domain.DeviceType;
import com.thdwjdrl.locus.core.domain.Telemetry;

/**
 * 디바이스 타입별 처리 전략 (양축 추상화의 DeviceType 축).
 *
 * <p>도메인 타입({@link Telemetry})만 다뤄 core 격리를 유지한다(app DTO를 모른다). 구현은 {@code app}에 두며, 새 타입은 구현을
 * 추가하기만 하면 된다(core 불변 — M3 증명 지점).
 */
public interface DeviceTypeHandler {

    /** 이 핸들러가 담당하는 디바이스 타입. */
    DeviceType deviceType();

    /**
     * 타입별 의미 검증. 형식·범위는 이미 DTO에서 걸렀고, 여기선 도메인 규칙(예: network=NONE인데 online=true 모순)을 본다. 위반 시 {@code
     * InvalidTelemetryException}.
     */
    void validate(Telemetry telemetry);
}
