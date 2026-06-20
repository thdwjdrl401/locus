package com.thdwjdrl.locus.app.telemetry;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 텔레메트리 수집 API.
 *
 * <p>{@code @Valid}가 봉투 형식·범위·시각을 검증(실패 시 전역 핸들러가 400). 통과하면 수집 서비스로. 202 Accepted — "수집을 받아들였다"(M2
 * 비동기 큐 전환과도 호환되는 의미).
 */
@RestController
@RequestMapping("/api/telemetry")
public class TelemetryController {

    private final TelemetryIngestService ingestService;

    public TelemetryController(TelemetryIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void ingest(@Valid @RequestBody TelemetryRequest request) {
        ingestService.ingest(request);
    }
}
