package com.thdwjdrl.locus.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thdwjdrl.locus.IntegrationTestBase;
import com.thdwjdrl.locus.app.device.DeviceRepository;
import com.thdwjdrl.locus.core.domain.DeviceType;
import com.thdwjdrl.locus.core.domain.Telemetry;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * AMR 수집 e2e (M3 추상화 검증) — 새 디바이스 타입이 core 변경 없이 같은 수집 경로로 저장되는지, 상태 모순은 드롭되는지.
 *
 * <p>로봇 위치는 개인정보가 아니라 게이트 없이 항상 저장된다(폰의 최소수집과 대비). odom·운영모드는 metrics(JSONB)로 보존된다.
 */
class AmrIngestIntegrationTest extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private TelemetryRepository telemetryRepository;
    @Autowired private DeviceRepository deviceRepository;

    @BeforeEach
    void clean() {
        telemetryRepository.deleteAll();
        deviceRepository.deleteAll();
    }

    private ResultActions postAmr(String estopState, boolean driving) throws Exception {
        String body =
                """
        {
          "deviceId":"amr-1","deviceType":"AMR","timestamp":"%s",
          "location":{"lat":37.5,"lng":127.0,"accuracy":0.1,"speed":0.5,"heading":90.0},
          "metrics":{
            "batteryPercent":72,"batteryStatus":"DISCHARGING","operatingMode":"AUTOMATIC",
            "driving":%s,"estopState":"%s","faultLevel":"OK",
            "odomX":10.0,"odomY":5.0,"odomTheta":1.57,"mapId":"site-1"
          }
        }"""
                        .formatted(Instant.now().toString(), driving, estopState);
        return mockMvc.perform(
                post("/api/telemetry").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void 정상_AMR_봉투는_저장되고_위치와_odom이_보존된다() throws Exception {
        postAmr("NOT_ESTOPPED", true).andExpect(status().isAccepted());

        assertThat(telemetryRepository.count()).isEqualTo(1);
        assertThat(deviceRepository.count()).isEqualTo(1);
        Telemetry t = telemetryRepository.findAll().get(0);
        assertThat(t.getDeviceType()).isEqualTo(DeviceType.AMR);
        // 로봇 위치는 게이트 없이 항상 수집.
        assertThat(t.getLocation()).isNotNull();
        assertThat(t.getLocation().getLatitude()).isEqualTo(37.5);
        assertThat(t.getMetrics()).containsKeys("operatingMode", "estopState", "odomX", "mapId");
    }

    @Test
    void estop인데_driving이면_400으로_드롭된다() throws Exception {
        postAmr("ESTOPPED", true).andExpect(status().isBadRequest());

        assertThat(telemetryRepository.count()).isZero();
    }
}
