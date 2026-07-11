package com.thdwjdrl.locus.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thdwjdrl.locus.IntegrationTestBase;
import com.thdwjdrl.locus.app.device.DeviceRepository;
import com.thdwjdrl.locus.core.domain.Device;
import com.thdwjdrl.locus.core.domain.DeviceType;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * stream 모드(M4b B) e2e — 실 Redis + 실 TimescaleDB로 스트림 fan-out을 검증한다.
 *
 * <p>PG는 {@link IntegrationTestBase}에서 상속, Redis는 이 클래스가 자체 컨테이너로 붙인다(기존 테스트에 영향 없게). 검증: (1)
 * stream 모드 컨텍스트가 뜬다(컨슈머 배선·그룹 생성 — 로컬 unit test로는 못 잡던 DI/기동), (2) 수집→Stream→{@code storage} 컨슈머가
 * 적재, (3) org 디바이스는 {@code monitoring} 컨슈머가 push(카운터로 확인).
 */
@TestPropertySource(
        properties = {
            "locus.ingest.mode=stream",
            "locus.ingest.batch-size=100",
            "locus.ingest.max-delay-ms=50"
        })
class StreamIngestIntegrationTest extends IntegrationTestBase {

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
    @Autowired private TelemetryRepository telemetryRepository;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private MeterRegistry meters;
    @Autowired private StreamStorageConsumer storageConsumer; // 스모크: stream 빈 배선 확인
    @Autowired private StreamMonitoringConsumer monitoringConsumer;
    @Autowired private StringRedisTemplate redis;
    @Autowired private IngestProperties ingestProps;

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
    void stream_모드_컨텍스트가_뜨고_컨슈머가_배선된다() {
        // 이 테스트가 도는 것 자체가 stream 모드 컨텍스트 기동 성공(오늘 겪은 DI 실패를 CI에서 잡는 지점).
        assertThat(storageConsumer).isNotNull();
        assertThat(monitoringConsumer).isNotNull();
        assertThat(storageConsumer.isRunning()).isTrue();
    }

    @Test
    void 수집이_Stream을_거쳐_storage_컨슈머로_적재된다() throws Exception {
        Instant base = Instant.now().minusSeconds(300);
        int n = 150;
        for (int i = 0; i < n; i++) {
            postTelemetry("stream-store", base.plusMillis(i).toString());
        }

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(telemetryRepository.count()).isEqualTo(n));

        // storage 컨슈머가 device를 insert-if-absent로 등록(라이브 status는 미설정 → 기본 UNKNOWN).
        Device device = deviceRepository.findByDeviceId("stream-store").orElseThrow();
        assertThat(device.getStatus().name()).isEqualTo("UNKNOWN");
    }

    @Test
    void org_디바이스는_monitoring_컨슈머가_push한다() throws Exception {
        // org 있는 디바이스만 push된다. 미리 org 부여(storage 등록은 DO NOTHING이라 기존 행·org 보존).
        deviceRepository.save(deviceWithOrg("stream-push", "org-7"));

        double before = meters.get("locus.push.sent").counter().count();
        postTelemetry("stream-push", Instant.now().minusSeconds(5).toString());

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(meters.get("locus.push.sent").counter().count())
                                        .isGreaterThan(before));
    }

    @Test
    void poison_엔트리는_드롭되고_storage와_monitoring_워커_모두_살아_계속_처리한다() throws Exception {
        // 스트림에 처리 불가 엔트리를 직접 XADD — 옛 코드는 storage(재시작 pending 회수)에서 워커를 죽였고,
        // monitoring에선 배치 중단으로 뒤따르는 정상 메시지를 starvation시켰다. 둘 다 같은 스트림을 읽는다.
        String stream = ingestProps.getStreamKey();
        // push 검증용 org 디바이스 — poison 뒤 정상 메시지가 실제로 push되어야 monitoring 생존 확인.
        deviceRepository.save(deviceWithOrg("after-poison", "org-9"));

        // (1) payload 필드 자체가 없음 = 트림된 유령 pending 재현(원래 크래시 원인).
        redis.opsForStream()
                .add(StreamRecords.newRecord().in(stream).ofMap(Map.of("garbage", "no-payload")));
        // (2) payload는 있으나 손상 JSON.
        redis.opsForStream()
                .add(
                        StreamRecords.newRecord()
                                .in(stream)
                                .ofMap(Map.of(StreamIngestWriter.PAYLOAD_FIELD, "{ not json")));

        double ingestPoisonBefore = meters.get("locus.ingest.poison").counter().count();
        double pushBefore = meters.get("locus.push.sent").counter().count();

        // 정상 엔트리 — 워커들이 살아있어야 적재(storage)+push(monitoring)된다.
        postTelemetry("after-poison", Instant.now().minusSeconds(5).toString());

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            // storage: poison 드롭 후 정상 1건 적재.
                            assertThat(telemetryRepository.count()).isEqualTo(1L);
                            assertThat(meters.get("locus.ingest.poison").counter().count())
                                    .isGreaterThanOrEqualTo(ingestPoisonBefore + 2);
                            // monitoring: poison에 배치가 안 막히고 정상 메시지를 push.
                            assertThat(meters.get("locus.push.sent").counter().count())
                                    .isGreaterThan(pushBefore);
                        });
    }

    private Device deviceWithOrg(String deviceId, String orgId) {
        Device d = new Device(deviceId, DeviceType.PHONE);
        d.setOrgId(orgId);
        return d;
    }
}
