package com.thdwjdrl.locus.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thdwjdrl.locus.IntegrationTestBase;
import com.thdwjdrl.locus.app.device.DeviceRepository;
import com.thdwjdrl.locus.core.domain.Device;
import com.thdwjdrl.locus.core.domain.Telemetry;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 수집 e2e — 실 TimescaleDB에 실제로 저장되는지(JSONB·임베디드·복합PK·upsert) 검증. */
class TelemetryIngestIntegrationTest extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private TelemetryRepository telemetryRepository;
    @Autowired private DeviceRepository deviceRepository;

    @BeforeEach
    void clean() {
        telemetryRepository.deleteAll();
        deviceRepository.deleteAll();
    }

    private ResultActions postTelemetry(String timestamp, String permission, boolean sharing)
            throws Exception {
        String body =
                """
        {
          "deviceId":"phone-1","deviceType":"PHONE","timestamp":"%s",
          "location":{"lat":37.0,"lng":127.0,"accuracy":5.0,"speed":1.0,"heading":90.0},
          "metrics":{
            "battery":{"level":80,"charging":false},
            "network":{"type":"CELLULAR","online":true},
            "activity":"WALKING","appState":"FOREGROUND","permission":"%s","sharingEnabled":%s
          }
        }"""
                        .formatted(timestamp, permission, sharing);
        return mockMvc.perform(
                post("/api/telemetry").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void 정상이면_telemetry와_device가_저장되고_JSON이_라운드트립된다() throws Exception {
        postTelemetry(Instant.now().toString(), "WHILE_IN_USE", true)
                .andExpect(status().isAccepted());

        assertThat(telemetryRepository.count()).isEqualTo(1);
        assertThat(deviceRepository.count()).isEqualTo(1);
        Telemetry t = telemetryRepository.findAll().get(0);
        assertThat(t.getLocation()).isNotNull();
        assertThat(t.getLocation().getLatitude()).isEqualTo(37.0);
        assertThat(t.getMetrics()).containsKey("battery").containsKey("network");
        assertThat(t.getReceivedAt()).isNotNull();
    }

    @Test
    void 공유off면_location_컬럼이_NULL로_저장된다() throws Exception {
        postTelemetry(Instant.now().toString(), "WHILE_IN_USE", false)
                .andExpect(status().isAccepted());

        assertThat(telemetryRepository.findAll().get(0).getLocation()).isNull();
    }

    @Test
    void 권한DENIED면_location_컬럼이_NULL로_저장된다() throws Exception {
        postTelemetry(Instant.now().toString(), "DENIED", true).andExpect(status().isAccepted());

        assertThat(telemetryRepository.findAll().get(0).getLocation()).isNull();
    }

    @Test
    void 같은_deviceId_timestamp_중복은_UNIQUE로_막혀_409() throws Exception {
        String ts = Instant.now().toString();
        postTelemetry(ts, "WHILE_IN_USE", true).andExpect(status().isAccepted());
        postTelemetry(ts, "WHILE_IN_USE", true).andExpect(status().isConflict());

        assertThat(telemetryRepository.count()).isEqualTo(1);
    }

    @Test
    void 신규_후_재전송이면_device를_upsert한다() throws Exception {
        postTelemetry(Instant.now().minusSeconds(10).toString(), "WHILE_IN_USE", true)
                .andExpect(status().isAccepted());
        Device first = deviceRepository.findByDeviceId("phone-1").orElseThrow();
        Instant firstSeen = first.getFirstSeenAt();

        postTelemetry(Instant.now().toString(), "WHILE_IN_USE", true)
                .andExpect(status().isAccepted());

        assertThat(deviceRepository.count()).isEqualTo(1);
        Device after = deviceRepository.findByDeviceId("phone-1").orElseThrow();
        assertThat(after.getFirstSeenAt()).isEqualTo(firstSeen); // 생성 시각 유지
        assertThat(after.getLastSeenAt()).isAfterOrEqualTo(firstSeen); // 최근 수신 갱신
        assertThat(telemetryRepository.count()).isEqualTo(2);
    }
}
