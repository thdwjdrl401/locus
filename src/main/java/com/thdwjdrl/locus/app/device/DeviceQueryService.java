package com.thdwjdrl.locus.app.device;

import com.thdwjdrl.locus.app.support.PageResponse;
import com.thdwjdrl.locus.core.domain.DeviceNotFoundException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 디바이스 조회 (M0: offset 페이징 — "순진하게 먼저". 커서 전환은 M7). */
@Service
public class DeviceQueryService {

    private final DeviceRepository deviceRepository;

    public DeviceQueryService(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<DeviceResponse> list(Pageable pageable) {
        return PageResponse.from(deviceRepository.findAll(pageable).map(DeviceResponse::from));
    }

    @Transactional(readOnly = true)
    public DeviceResponse getByDeviceId(String deviceId) {
        return deviceRepository
                .findByDeviceId(deviceId)
                .map(DeviceResponse::from)
                .orElseThrow(() -> new DeviceNotFoundException(deviceId));
    }
}
