package com.thdwjdrl.locus.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thdwjdrl.locus.IntegrationTestBase;
import com.thdwjdrl.locus.app.device.DeviceRepository;
import com.thdwjdrl.locus.core.domain.Device;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 큐 모드(M1 A2) e2e — 인메모리 큐 + 배치 워커가 실 TimescaleDB에 적재하는지 검증.
 *
 * <p>direct와 다른 점: 수집은 항상 202(비동기)이고, 적재는 워커가 배치로 한다(그래서 {@code await}로 flush를 기다린다). 중복은 {@code ON
 * CONFLICT DO NOTHING}이라 409가 아니라 조용히 버려진다. 작은 batch/delay로 테스트를 빠르게.
 */
@TestPropertySource(
        properties = {
            "locus.ingest.mode=queue",
            "locus.ingest.batch-size=100",
            "locus.ingest.max-delay-ms=50"
        })
class TelemetryQueueIngestIntegrationTest extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private TelemetryRepository telemetryRepository;
    @Autowired private DeviceRepository deviceRepository;

    @BeforeEach
    void clean() {
        telemetryRepository.deleteAll();
        deviceRepository.deleteAll();
    }

    private void postTelemetry(String deviceId, String timestamp) throws Exception {
        String body =
                """
        {
          "deviceId":"%s","deviceType":"PHONE","timestamp":"%s",
          "location":{"lat":37.0,"lng":127.0,"accuracy":5.0,"speed":1.0,"heading":90.0},
          "metrics":{
            "battery":{"level":80,"charging":false},
            "network":{"type":"CELLULAR","online":true},
            "activity":"WALKING","appState":"FOREGROUND","permission":"WHILE_IN_USE","sharingEnabled":true
          }
        }"""
                        .formatted(deviceId, timestamp);
        mockMvc.perform(
                        post("/api/telemetry")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isAccepted());
    }

    @Test
    void N건을_큐로_받아_배치로_적재하고_device는_1행_등록된다() throws Exception {
        Instant base = Instant.now().minusSeconds(300);
        int n = 250; // batch-size(100)보다 커서 여러 배치로 나뉘어 flush
        for (int i = 0; i < n; i++) {
            postTelemetry("phone-1", base.plusMillis(i).toString());
        }

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(telemetryRepository.count()).isEqualTo(n));

        // 같은 deviceId 250건 → device는 1행 insert-if-absent(배치 내 dedupe + ON CONFLICT DO NOTHING).
        // 라이브 상태(status·last_seen)는 미기록 — 최신상태 프로젝션이 소유(강등).
        assertThat(deviceRepository.count()).isEqualTo(1);
        Device device = deviceRepository.findByDeviceId("phone-1").orElseThrow();
        assertThat(device.getStatus().name()).isEqualTo("UNKNOWN");
        assertThat(device.getLastSeenAt()).isNull();
    }

    @Test
    void 중복_recordedAt은_ON_CONFLICT_DO_NOTHING으로_1행만_남고_409가_아니다() throws Exception {
        String ts = Instant.now().minusSeconds(10).toString();
        postTelemetry("phone-dup", ts); // 둘 다 202 (비동기 — 충돌 응답 없음)
        postTelemetry("phone-dup", ts);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () ->
                                assertThat(
                                                telemetryRepository
                                                        .findByDeviceIdOrderByRecordedAtDesc(
                                                                "phone-dup",
                                                                org.springframework.data.domain
                                                                        .PageRequest.of(0, 10))
                                                        .getTotalElements())
                                        .isEqualTo(1));
    }
}
