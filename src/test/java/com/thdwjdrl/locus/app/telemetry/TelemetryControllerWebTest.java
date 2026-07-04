package com.thdwjdrl.locus.app.telemetry;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thdwjdrl.locus.core.domain.DeviceType;
import com.thdwjdrl.locus.core.domain.InvalidTelemetryException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 웹 계층(HTTP·검증·에러응답)만 — 서비스는 mock. */
@WebMvcTest(TelemetryController.class)
class TelemetryControllerWebTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private TelemetryIngestService ingestService;
    @MockitoBean private TelemetryQueryService queryService;

    private String json(String timestamp, String deviceField, String lat) {
        return """
        {
          %s
          "deviceType":"PHONE","timestamp":"%s",
          "location":{"lat":%s,"lng":127.0,"accuracy":5.0,"speed":1.0,"heading":90.0},
          "metrics":{
            "battery":{"level":80,"charging":false},
            "network":{"type":"CELLULAR","online":true},
            "activity":"WALKING","appState":"FOREGROUND","permission":"WHILE_IN_USE","sharingEnabled":true
          }
        }"""
                .formatted(deviceField, timestamp, lat);
    }

    private String validJson() {
        return json(Instant.now().toString(), "\"deviceId\":\"phone-1\",", "37.0");
    }

    @Test
    void 정상_봉투는_202이고_수집서비스를_호출한다() throws Exception {
        mockMvc.perform(
                        post("/api/telemetry")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validJson()))
                .andExpect(status().isAccepted());
        verify(ingestService).ingest(any());
    }

    @Test
    void 위도_범위초과는_400_VALIDATION_FAILED() throws Exception {
        String bad = json(Instant.now().toString(), "\"deviceId\":\"phone-1\",", "100.0");
        mockMvc.perform(post("/api/telemetry").contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void deviceId_누락은_400() throws Exception {
        String bad = json(Instant.now().toString(), "", "37.0"); // deviceId 필드 없음
        mockMvc.perform(post("/api/telemetry").contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 미래_타임스탬프는_400() throws Exception {
        String future = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        String bad = json(future, "\"deviceId\":\"phone-1\",", "37.0");
        mockMvc.perform(post("/api/telemetry").contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 알수없는_deviceType은_400_MALFORMED_REQUEST() throws Exception {
        String bad = validJson().replace("\"PHONE\"", "\"ROBOT\"");
        mockMvc.perform(post("/api/telemetry").contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void 전략_검증_실패는_400_INVALID_TELEMETRY() throws Exception {
        doThrow(new InvalidTelemetryException("모순")).when(ingestService).ingest(any());
        mockMvc.perform(
                        post("/api/telemetry")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validJson()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TELEMETRY"));
    }

    private TelemetryResponse sample(String deviceId) {
        return new TelemetryResponse(
                deviceId,
                DeviceType.PHONE,
                Instant.parse("2026-06-22T00:00:00Z"),
                Instant.parse("2026-06-22T00:00:01Z"),
                new TelemetryResponse.LocationDto(37.0, 127.0, 5.0, null, 1.0, 90.0),
                Map.of());
    }

    @Test
    void 디바이스_최신조회는_200과_위치를_반환() throws Exception {
        when(queryService.latest("phone-1")).thenReturn(Optional.of(sample("phone-1")));
        mockMvc.perform(get("/api/telemetry/phone-1/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value("phone-1"))
                .andExpect(jsonPath("$.location.lat").value(37.0));
    }

    @Test
    void 최신_텔레메트리_없으면_404() throws Exception {
        when(queryService.latest("ghost")).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/telemetry/ghost/latest")).andExpect(status().isNotFound());
    }

    @Test
    void 전체_최신목록은_200_배열() throws Exception {
        // org 파라미터 없음 → 컨트롤러가 latestPerDevice(null) 호출(전체=super-admin).
        when(queryService.latestPerDevice(null))
                .thenReturn(List.of(sample("phone-1"), sample("phone-2")));
        mockMvc.perform(get("/api/telemetry/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deviceId").value("phone-1"))
                .andExpect(jsonPath("$[1].deviceId").value("phone-2"));
    }

    @Test
    void 조직_스코프_최신목록은_그_조직으로_조회() throws Exception {
        when(queryService.latestPerDevice("org-3")).thenReturn(List.of(sample("phone-9")));
        mockMvc.perform(get("/api/telemetry/latest").param("org", "org-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deviceId").value("phone-9"));
    }
}
