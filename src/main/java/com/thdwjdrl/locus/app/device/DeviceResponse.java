package com.thdwjdrl.locus.app.device;

import com.thdwjdrl.locus.core.domain.Device;
import com.thdwjdrl.locus.core.domain.DeviceStatus;
import com.thdwjdrl.locus.core.domain.DeviceType;
import java.time.Instant;

/** 디바이스 조회 응답. */
public record DeviceResponse(
        String deviceId,
        DeviceType deviceType,
        DeviceStatus status,
        Instant firstSeenAt,
        Instant lastSeenAt) {

    public static DeviceResponse from(Device device) {
        return new DeviceResponse(
                device.getDeviceId(),
                device.getDeviceType(),
                device.getStatus(),
                device.getFirstSeenAt(),
                device.getLastSeenAt());
    }
}
