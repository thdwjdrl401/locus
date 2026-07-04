package com.thdwjdrl.locus.app.simulator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 시뮬레이터 설정 (application-simulator.yml의 {@code locus.simulator.*}). */
@Component
@Profile("simulator")
@ConfigurationProperties(prefix = "locus.simulator")
public class SimulatorProperties {

    /** 가상 폰 수. */
    private int phoneCount = 50;

    /** 가상 AMR(로봇) 수. */
    private int amrCount = 0;

    /** 전송 주기(ms). */
    private long intervalMs = 1000;

    /** 텔레메트리 전송 대상(자기 자신 또는 별도 인스턴스). */
    private String targetBaseUrl = "http://localhost:8093";

    public int getPhoneCount() {
        return phoneCount;
    }

    public void setPhoneCount(int phoneCount) {
        this.phoneCount = phoneCount;
    }

    public int getAmrCount() {
        return amrCount;
    }

    public void setAmrCount(int amrCount) {
        this.amrCount = amrCount;
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
