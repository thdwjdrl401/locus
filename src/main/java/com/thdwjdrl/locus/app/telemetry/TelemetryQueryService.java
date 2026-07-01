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
    private final Optional<LatestStateLookup> latestCache; // cache 모드에서만 빈 존재

    public TelemetryQueryService(
            TelemetryRepository telemetryRepository, Optional<LatestStateLookup> latestCache) {
        this.telemetryRepository = telemetryRepository;
        this.latestCache = latestCache;
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

    /**
     * 디바이스별 최신 프레임(관제 지도). {@code org}=null/blank → 전체(super-admin), 지정 → 그 조직 스코프.
     *
     * <p>백엔드는 {@code locus.read.latest-source}: db(DISTINCT ON) | cache(Redis) — 캐시 빈 존재로 분기(M4a
     * before/after 토글). 캐시 경로는 DB 트랜잭션을 잡지 않는다(불필요한 커넥션 점유 회피 — 측정 정확성).
     */
    public List<TelemetryResponse> latestPerDevice(String org) {
        boolean all = (org == null || org.isBlank());
        if (latestCache.isPresent()) {
            LatestStateLookup cache = latestCache.get();
            return all ? cache.findAll() : cache.findByOrg(org);
        }
        var rows =
                all
                        ? telemetryRepository.findLatestPerDevice()
                        : telemetryRepository.findLatestByOrg(org);
        return rows.stream().map(TelemetryResponse::from).toList();
    }
}
