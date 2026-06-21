package com.thdwjdrl.locus.app.simulator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 시뮬레이터 설정 (application-simulator.yml의 {@code locus.simulator.*}). */
@Component
@Profile("simulator")
@ConfigurationProperties(prefix = "locus.simulator")
public class SimulatorProperties {

    /** 가상 디바이스 수. */
    private int deviceCount = 50;

    /** 전송 주기(ms). */
    private long intervalMs = 1000;

    /** 텔레메트리 전송 대상(자기 자신 또는 별도 인스턴스). */
    private String targetBaseUrl = "http://localhost:8093";

    public int getDeviceCount() {
        return deviceCount;
    }

    public void setDeviceCount(int deviceCount) {
        this.deviceCount = deviceCount;
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    public String getTargetBaseUrl() {
        return targetBaseUrl;
    }

    public void setTargetBaseUrl(String targetBaseUrl) {
        this.targetBaseUrl = targetBaseUrl;
    }
}
