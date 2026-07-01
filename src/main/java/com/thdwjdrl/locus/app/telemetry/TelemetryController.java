package com.thdwjdrl.locus.app.telemetry;

import com.thdwjdrl.locus.app.support.PageResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 텔레메트리 수집·조회 API.
 *
 * <p>수집: {@code @Valid}가 봉투 형식·범위·시각을 검증(실패 시 전역 핸들러가 400). 통과하면 수집 서비스로. 202 Accepted(M2 비동기 큐 전환과
 * 호환).
 *
 * <p>조회(관제): 디바이스별 최신 위치/이력. 실시간 푸시는 M4 — M0는 폴링(새로고침 조회)으로 단순하게.
 */
@RestController
@RequestMapping("/api/telemetry")
public class TelemetryController {

    private final TelemetryIngestService ingestService;
    private final TelemetryQueryService queryService;

    public TelemetryController(
            TelemetryIngestService ingestService, TelemetryQueryService queryService) {
        this.ingestService = ingestService;
        this.queryService = queryService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void ingest(@Valid @RequestBody TelemetryRequest request) {
        ingestService.ingest(request);
    }

    /** 디바이스별 최신 프레임 목록 — 관제 지도용. {@code org} 지정 시 그 조직, 없으면 전체(super-admin). */
    @GetMapping("/latest")
    public List<TelemetryResponse> latestPerDevice(@RequestParam(required = false) String org) {
        return queryService.latestPerDevice(org);
    }

    /** 한 디바이스의 최신 프레임. 텔레메트리 없으면 404. */
    @GetMapping("/{deviceId}/latest")
    public ResponseEntity<TelemetryResponse> latest(@PathVariable String deviceId) {
        return ResponseEntity.of(queryService.latest(deviceId));
    }

    /** 한 디바이스의 이력(최신순, offset 페이징). */
    @GetMapping("/{deviceId}")
    public PageResponse<TelemetryResponse> history(
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queryService.history(deviceId, PageRequest.of(page, size));
    }
}
