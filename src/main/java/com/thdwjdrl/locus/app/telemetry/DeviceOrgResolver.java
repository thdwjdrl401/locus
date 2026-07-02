package com.thdwjdrl.locus.app.telemetry;

import com.thdwjdrl.locus.app.device.DeviceRepository;
import com.thdwjdrl.locus.core.domain.Device;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * deviceId → orgId 조회(인메모리 캐시). monitoring 컨슈머가 push 라우팅 토픽을 정할 때 쓴다.
 *
 * <p>org 해석을 **적재 핫패스가 아니라 여기서** 하는 게 핵심 — 적재(XADD)는 org를 모른 채 빠르게, 라우팅은 push 소비 시점에. org는 거의 안
 * 바뀌므로(스펙 #2, 이동 드묾) 미스 시 DB 1회 조회 후 캐시. org 이동 시 캐시 무효화는 후속(현재 TTL·재로드 없음).
 */
@Component
public class DeviceOrgResolver {

    private final DeviceRepository devices;
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    public DeviceOrgResolver(DeviceRepository devices) {
        this.devices = devices;
    }

    /** deviceId의 org. 없으면(미enroll) null → 호출자가 push 스킵. */
    public String orgOf(String deviceId) {
        String cached = cache.get(deviceId);
        if (cached != null) {
            return cached;
        }
        String org = devices.findByDeviceId(deviceId).map(Device::getOrgId).orElse(null);
        if (org != null) {
            cache.put(deviceId, org); // null은 캐시 안 함(ConcurrentHashMap null 불가 + 미enroll은 드묾)
        }
        return org;
    }
}
