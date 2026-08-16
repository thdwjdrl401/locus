package com.thdwjdrl.locus.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thdwjdrl.locus.IntegrationTestBase;
import com.thdwjdrl.locus.app.device.DeviceRepository;
import com.thdwjdrl.locus.core.domain.Device;
import com.thdwjdrl.locus.core.domain.DeviceType;
import com.thdwjdrl.locus.core.domain.Telemetry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 수집으로 만들어진 디바이스가 <b>조직 스코프 조회에 실제로 보이는지</b> 검증.
 *
 * <p>이 경로가 끊기면 관제 화면이 조용히 빈 채로 뜬다 — 지도는 {@code ?org=}로 조회하고 {@code /topic/org/{org}}를 구독하는데, {@code
 * org_id}가 null이면 조회는 빈 배열이고 monitoring 컨슈머는 전량 스킵한다. 실제로 그렇게 비어 있던 것을 막는 회귀 테스트다 (수집 경로에 {@code
 * org_id} 기록자가 없었음).
 */
@TestPropertySource(properties = "locus.ingest.default-org=org-0")
class DeviceOrgScopeIntegrationTest extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private TelemetryRepository telemetryRepository;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private TelemetryBatchDao batchDao;

    @BeforeEach
    void clean() {
        telemetryRepository.deleteAll();
        deviceRepository.deleteAll();
    }

    private void postTelemetry(String deviceId) throws Exception {
        String body =
                """
        {
          "deviceId":"%s","deviceType":"PHONE","timestamp":"%s",
          "location":{"lat":37.0,"lng":127.0,"accuracy":5.0},
          "metrics":{"permission":"WHILE_IN_USE","sharingEnabled":true}
        }"""
                        .formatted(deviceId, Instant.now().toString());
        mockMvc.perform(
                        post("/api/telemetry")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isAccepted());
    }

    @Test
    void 수집된_디바이스가_조직_스코프_조회에_보인다() throws Exception {
        postTelemetry("phone-1");

        assertThat(deviceRepository.findByDeviceId("phone-1"))
                .get()
                .extracting(Device::getOrgId)
                .isEqualTo("org-0");

        mockMvc.perform(get("/api/telemetry/latest").param("org", "org-0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].deviceId").value("phone-1"));
    }

    @Test
    void 다른_조직으로_조회하면_안_보인다() throws Exception {
        postTelemetry("phone-1");

        mockMvc.perform(get("/api/telemetry/latest").param("org", "org-9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** 배치 경로(queue·stream이 공유하는 raw SQL upsert)도 org를 넣는지 — 컬럼 추가가 SQL에만 있어 별도 확인. */
    @Test
    void 배치_적재도_새_디바이스에_org를_넣는다() {
        Instant now = Instant.now();
        batchDao.persistBatch(
                List.of(new Telemetry("amr-1", DeviceType.AMR, now, now, null, Map.of())));

        assertThat(deviceRepository.findByDeviceId("amr-1"))
                .get()
                .extracting(Device::getOrgId)
                .isEqualTo("org-0");
    }

    /** 이미 조직이 배정된 디바이스를 이후 수집이 되돌리지 않는지(배치 경로의 DO UPDATE에 org_id가 없어야 함). */
    @Test
    void 배치_적재는_기존_org를_덮어쓰지_않는다() {
        Instant now = Instant.now();
        Device enrolled = new Device("amr-2", DeviceType.AMR);
        enrolled.setOrgId("org-9");
        deviceRepository.save(enrolled);

        batchDao.persistBatch(
                List.of(new Telemetry("amr-2", DeviceType.AMR, now, now, null, Map.of())));

        assertThat(deviceRepository.findByDeviceId("amr-2"))
                .get()
                .extracting(Device::getOrgId)
                .isEqualTo("org-9");
    }
}
