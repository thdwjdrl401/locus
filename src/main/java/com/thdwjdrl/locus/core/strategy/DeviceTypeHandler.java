package com.thdwjdrl.locus.core.strategy;

import com.thdwjdrl.locus.core.domain.DeviceType;
import com.thdwjdrl.locus.core.domain.Location;
import com.thdwjdrl.locus.core.domain.Telemetry;
import java.util.Map;

/**
 * 디바이스 타입별 처리 전략 (양축 추상화의 DeviceType 축).
 *
 * <p>도메인 타입({@link Telemetry}·{@link Location})만 다뤄 core 격리를 유지한다(app DTO를 모른다). 구현은 {@code app}에
 * 두며, 새 타입은 구현을 추가하기만 하면 된다(core 불변 — M3 증명 지점).
 */
public interface DeviceTypeHandler {

    /** 이 핸들러가 담당하는 디바이스 타입. */
    DeviceType deviceType();

    /**
     * 수집 게이트: 타입별 정책에 따라 저장할 위치를 정한다. 기본은 위치 유지(로봇 등 프라이버시 게이트가 없는 타입). 폰처럼 최소수집이 필요한 타입은
     * metrics(권한·공유 등)를 보고 위치를 버릴 수 있다(null 반환). raw는 수집 봉투의 원본 위치, 반환값이 저장될 위치다.
     */
    default Location gate(Location raw, Map<String, Object> metrics) {
        return raw;
    }

    /**
     * 타입별 의미 검증. 공통칸 형식·범위는 이미 DTO에서 걸렀고, 여기선 도메인 규칙(예: network=NONE인데 online=true 모순)과 metrics 파싱을
     * 본다. 위반 시 {@code InvalidTelemetryException}.
     */
    void validate(Telemetry telemetry);
}
