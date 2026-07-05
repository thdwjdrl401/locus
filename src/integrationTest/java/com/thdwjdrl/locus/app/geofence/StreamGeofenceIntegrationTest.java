package com.thdwjdrl.locus.app.geofence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thdwjdrl.locus.IntegrationTestBase;
import com.thdwjdrl.locus.app.device.DeviceRepository;
import com.thdwjdrl.locus.core.domain.Device;
import com.thdwjdrl.locus.core.domain.DeviceType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 지오펜스 판정 CG(M5) e2e — 실 Redis + TimescaleDB. 시드된 원형 존을 두고 수집→Stream→geofence 컨슈머가 ENTER/EXIT 이벤트를
 * 내는지 카운터로 검증. 존은 {@code @TestPropertySource}로 주입(config 시딩 경로 확인).
 */
@TestPropertySource(
        properties = {
            "locus.ingest.mode=stream",
            "locus.ingest.batch-size=100",
            "locus.ingest.max-delay-ms=50",
            "locus.geofence.seeded[0].id=zone-1",
            "locus.geofence.seeded[0].org=org-geo",
            "locus.geofence.seeded[0].name=Zone 1",
            "locus.geofence.seeded[0].centerLat=37.5",
            "locus.geofence.seeded[0].centerLng=127.0",
            "locus.geofence.seeded[0].radiusMeters=100"
        })
class StreamGeofenceIntegrationTest extends IntegrationTestBase {

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private MeterRegistry meters;
    @Autowired private StreamGeofenceConsumer geofenceConsumer; // 스모크: geofence 빈 배선 확인
    @Autowired private GeofenceCatalog catalog;

    @BeforeEach
    void clean() {
        deviceRepository.deleteAll();
    }

    @Test
    void geofence_컨슈머와_시드_존이_배선된다() {
        assertThat(geofenceConsumer).isNotNull();
        assertThat(geofenceConsumer.isRunning()).isTrue();
        assertThat(catalog.zonesForOrg("org-geo")).hasSize(1);
    }

    @Test
    void 존_안팎_텔레메트리가_ENTER_EXIT_이벤트를_낸다() throws Exception {
        deviceRepository.save(deviceWithOrg("geo-dev", "org-geo"));
        double enterBefore = count("ENTER");
        double exitBefore = count("EXIT");

        postAt("geo-dev", 37.5, 127.0); // 존 중심 → 첫 관측이 안 → ENTER
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(count("ENTER")).isGreaterThan(enterBefore));

        postAt("geo-dev", 37.6, 127.0); // 존 밖(~11km) → EXIT
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(count("EXIT")).isGreaterThan(exitBefore));
    }

    private double count(String type) {
        Counter c = meters.find("locus.geofence.events").tag("type", type).counter();
        return c == null ? 0.0 : c.count();
    }

    private void postAt(String deviceId, double lat, double lng) throws Exception {
        String body =
                """
        {
          "deviceId":"%s","deviceType":"PHONE","timestamp":"%s",
          "location":{"lat":%s,"lng":%s,"accuracy":5.0,"speed":0.0,"heading":0.0},
          "metrics":{
            "battery":{"level":80,"charging":false},
            "network":{"type":"CELLULAR","online":true},
            "activity":"STILL","appState":"FOREGROUND","permission":"WHILE_IN_USE","sharingEnabled":true
          }
        }"""
                        .formatted(deviceId, Instant.now().minusSeconds(5).toString(), lat, lng);
        mockMvc.perform(
                        post("/api/telemetry")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isAccepted());
    }

    private Device deviceWithOrg(String deviceId, String orgId) {
        Device d = new Device(deviceId, DeviceType.PHONE);
        d.setOrgId(orgId);
        return d;
    }
}
