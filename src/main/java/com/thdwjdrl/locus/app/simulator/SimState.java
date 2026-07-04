package com.thdwjdrl.locus.app.simulator;

/**
 * 한 가상 디바이스의 진행 상태(가변). 디바이스(=가상 스레드)마다 1개라 스레드 간 공유되지 않는다.
 *
 * <p>타입별 프로파일이 자기 필드만 해석한다. 폰은 {@code lat/lng}(현재 위치)·{@code batteryLevel/online}, AMR은 {@code
 * lat/lng}(사이트 anchor)·odom·배터리·주행 상태를 쓴다.
 */
public class SimState {

    final String deviceId;
    double lat;
    double lng;
    long tick = 0;

    // 폰 상태
    int batteryLevel = 100;
    boolean online = true;

    // AMR 상태 (odom = 맵 기준 위치, m·rad)
    double odomX = 0;
    double odomY = 0;
    double odomTheta = 0;
    int waypointIndex = 0;
    int batteryPercent = 100;
    boolean charging = false;

    public SimState(String deviceId, double lat, double lng) {
        this.deviceId = deviceId;
        this.lat = lat;
        this.lng = lng;
    }
}
