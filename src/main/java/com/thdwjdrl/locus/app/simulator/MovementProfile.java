package com.thdwjdrl.locus.app.simulator;

import com.thdwjdrl.locus.app.telemetry.TelemetryRequest;
import com.thdwjdrl.locus.core.domain.DeviceType;

/**
 * 디바이스 타입별 움직임/상태 생성 전략 (시뮬레이터 쪽 추상화 축).
 *
 * <p>봉투(app DTO)를 만들므로 core가 아니라 app에 둔다. 새 디바이스 타입(M3 태그 등)은 이와 같은 형태의 프로파일을 추가하면 되고 core·수집 경로는 안
 * 바뀐다.
 */
public interface MovementProfile {

    DeviceType deviceType();

    /** 상태를 한 프레임 진전시키고 보낼 봉투를 만든다. */
    TelemetryRequest step(SimState state);
}
