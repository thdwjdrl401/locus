package com.thdwjdrl.locus.app.device;

import com.thdwjdrl.locus.core.domain.Device;
import com.thdwjdrl.locus.core.domain.DeviceType;
import java.time.Instant;

/**
 * 디바이스 조회 응답 — 레지스트리 사실만(존재·타입·최초 접속).
 *
 * <p>라이브 상태(status·last_seen)는 여기서 안 준다. last_seen은 최신 텔레메트리 시각이라 최신상태 프로젝션({@code GET
 * /api/telemetry/latest})에서 파생되고, status는 그 staleness로 파생한다(M4 스펙 "status=파생"). first_seen은 오래된
 * 텔레메트리가 retention으로 삭제돼 재계산 불가라 레지스트리에 남는다.
 */
public record DeviceResponse(String deviceId, DeviceType deviceType, Instant firstSeenAt) {

    public static DeviceResponse from(Device device) {
        return new DeviceResponse(
                device.getDeviceId(), device.getDeviceType(), device.getFirstSeenAt());
    }
}
