package com.thdwjdrl.locus.app.simulator;

/** 한 가상 디바이스의 진행 상태(가변). 디바이스(=가상 스레드)마다 1개라 스레드 간 공유되지 않는다. */
public class SimState {

    final String deviceId;
    double lat;
    double lng;
    int batteryLevel = 100;
    boolean online = true;
    long tick = 0;

    public SimState(String deviceId, double lat, double lng) {
        this.deviceId = deviceId;
        this.lat = lat;
        this.lng = lng;
    }
}
