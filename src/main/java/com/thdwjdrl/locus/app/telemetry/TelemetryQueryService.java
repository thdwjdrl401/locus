package com.thdwjdrl.locus.app.telemetry;

import com.thdwjdrl.locus.app.support.PageResponse;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 텔레메트리 조회 (M0: naive 최신조회 + offset 이력 — "순진하게 먼저". 캐시는 M4, 커서는 M7). */
@Service
public class TelemetryQueryService {

    private final TelemetryRepository telemetryRepository;

    public TelemetryQueryService(TelemetryRepository telemetryRepository) {
        this.telemetryRepository = telemetryRepository;
    }

    /** 한 디바이스의 최신 프레임(없으면 empty → 컨트롤러가 404). */
    @Transactional(readOnly = true)
    public Optional<TelemetryResponse> latest(String deviceId) {
        return telemetryRepository
                .findTopByDeviceIdOrderByRecordedAtDesc(deviceId)
                .map(TelemetryResponse::from);
    }

    /** 한 디바이스의 이력(최신순 페이징). */
    @Transactional(readOnly = true)
    public PageResponse<TelemetryResponse> history(String deviceId, Pageable pageable) {
        return PageResponse.from(
                telemetryRepository
                        .findByDeviceIdOrderByRecordedAtDesc(deviceId, pageable)
                        .map(TelemetryResponse::from));
    }

    /** 디바이스별 최신 프레임 목록(관제 지도). */
    @Transactional(readOnly = true)
    public List<TelemetryResponse> latestPerDevice() {
        return telemetryRepository.findLatestPerDevice().stream()
                .map(TelemetryResponse::from)
                .toList();
    }
}
