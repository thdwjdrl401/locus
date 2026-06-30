package com.thdwjdrl.locus.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thdwjdrl.locus.IntegrationTestBase;
import com.thdwjdrl.locus.core.domain.DeviceType;
import com.thdwjdrl.locus.core.domain.Location;
import com.thdwjdrl.locus.core.domain.Telemetry;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** 조회 e2e — 실 TimescaleDB에서 naive 최신조회(상관 서브쿼리)가 디바이스당 최신 1건을 정확히 고르는지. */
class TelemetryQueryIntegrationTest extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private TelemetryRepository telemetryRepository;

    private static final Instant T0 = Instant.parse("2026-06-22T00:00:00Z");

    @BeforeEach
    void clean() {
        telemetryRepository.deleteAll();
    }

    private void save(String deviceId, Instant recordedAt, double lat) {
        telemetryRepository.save(
                new Telemetry(
                        deviceId,
                        DeviceType.PHONE,
                        recordedAt,
                        recordedAt.plusMillis(50),
                        new Location(lat, 127.0, 5.0, null, 1.0, 90.0),
                        Map.of()));
    }

    @Test
    void 디바이스별_최신_1건만_고른다() {
        // phone-1: 3 프레임(가장 최근 = T0+20s, lat 37.2)
        save("phone-1", T0, 37.0);
        save("phone-1", T0.plusSeconds(10), 37.1);
        save("phone-1", T0.plusSeconds(20), 37.2);
        // phone-2: 1 프레임
        save("phone-2", T0.plusSeconds(5), 36.5);

        var latest = telemetryRepository.findLatestPerDevice();
        assertThat(latest).hasSize(2); // 디바이스당 1건
        assertThat(latest)
                .filteredOn(t -> t.getDeviceId().equals("phone-1"))
                .singleElement()
                .satisfies(
                        t -> {
                            assertThat(t.getRecordedAt()).isEqualTo(T0.plusSeconds(20));
                            assertThat(t.getLocation().getLatitude()).isEqualTo(37.2);
                        });
    }

    @Test
    void 단건_최신조회는_가장_최근_프레임() {
        save("phone-1", T0, 37.0);
        save("phone-1", T0.plusSeconds(30), 37.3);

        var top = telemetryRepository.findTopByDeviceIdOrderByRecordedAtDesc("phone-1");
        assertThat(top).isPresent();
        assertThat(top.get().getRecordedAt()).isEqualTo(T0.plusSeconds(30));
    }

    @Test
    void GET_latest_최신조회_API() throws Exception {
        save("phone-1", T0, 37.0);
        save("phone-1", T0.plusSeconds(10), 37.1);

        mockMvc.perform(get("/api/telemetry/phone-1/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value("phone-1"))
                .andExpect(jsonPath("$.location.lat").value(37.1));

        mockMvc.perform(get("/api/telemetry/ghost/latest")).andExpect(status().isNotFound());
    }

    @Test
    void GET_전체_최신목록_API() throws Exception {
        save("phone-1", T0, 37.0);
        save("phone-2", T0.plusSeconds(5), 36.5);

        mockMvc.perform(get("/api/telemetry/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }
}
