package com.thdwjdrl.locus.core.domain;

/** 조회한 deviceId의 디바이스가 없을 때. (전역 핸들러에서 404로 매핑) */
public class DeviceNotFoundException extends RuntimeException {

    public DeviceNotFoundException(String deviceId) {
        super("디바이스를 찾을 수 없습니다: " + deviceId);
    }
}
