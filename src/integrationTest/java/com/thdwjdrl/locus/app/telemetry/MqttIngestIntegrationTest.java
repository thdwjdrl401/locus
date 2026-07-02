package com.thdwjdrl.locus.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.thdwjdrl.locus.IntegrationTestBase;
import com.thdwjdrl.locus.app.device.DeviceRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * M-MQTT e2e — 실 Mosquitto + 실 TimescaleDB로 MQTT 수집 경로를 검증한다.
 *
 * <p>PG는 {@link IntegrationTestBase}에서 상속, Mosquitto는 이 클래스가 자체 컨테이너로 붙인다. 검증: (1) {@code
 * enabled=true} 컨텍스트가 뜨고 {@link PahoMqttSubscriber}가 구독 연결(로컬 unit test로는 못 잡는 DI/기동), (2) MQTT 발행
 * → HTTP와 같은 {@link TelemetryIngestService}로 합류해 DB 적재, (3) 검증 실패 봉투는 드롭되고 파이프라인은 유지.
 */
@TestPropertySource(
        properties = {
            "locus.mqtt.enabled=true",
            "locus.ingest.batch-size=100",
            "locus.ingest.max-delay-ms=50"
        })
class MqttIngestIntegrationTest extends IntegrationTestBase {

    private static final String TOPIC = "telemetry";

    static final GenericContainer<?> MOSQUITTO =
            new GenericContainer<>(DockerImageName.parse("eclipse-mosquitto:2.0"))
                    .withExposedPorts(1883)
                    // 측정 브로커와 같은 설정(익명 허용·영속화 끔) — 파일 의존 없이 인라인 주입.
                    .withCopyToContainer(
                            Transferable.of(
                                    "listener 1883\nallow_anonymous true\npersistence false\n"),
                            "/mosquitto/config/mosquitto.conf")
                    .waitingFor(Wait.forListeningPort());

    static {
        MOSQUITTO.start();
    }

    @DynamicPropertySource
    static void mqttProps(DynamicPropertyRegistry registry) {
        registry.add("locus.mqtt.url", MqttIngestIntegrationTest::brokerUrl);
    }

    private static String brokerUrl() {
        return "tcp://" + MOSQUITTO.getHost() + ":" + MOSQUITTO.getMappedPort(1883);
    }

    @Autowired private TelemetryRepository telemetryRepository;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private MqttSubscriber subscriber; // 이음새 — 구현체는 Paho

    @BeforeEach
    void clean() {
        telemetryRepository.deleteAll();
        deviceRepository.deleteAll();
    }

    @Test
    void mqtt_모드_컨텍스트가_뜨고_구독자가_붙는다() {
        // 이 테스트가 도는 것 자체가 enabled 컨텍스트 기동 + 브로커 구독 성공.
        assertThat(subscriber).isInstanceOf(PahoMqttSubscriber.class);
        assertThat(subscriber.isRunning()).isTrue();
    }

    @Test
    void mqtt로_발행한_텔레메트리가_DB에_적재된다() throws Exception {
        String ts = Instant.now().minusSeconds(5).toString();
        // 구독 준비 레이스 회피: 멱등 페이로드(동일 device+ts)를 여러 번 발행 → ON CONFLICT로 최종 1행.
        publishRepeated(payload("mqtt-store", ts), 5);

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(telemetryRepository.count()).isEqualTo(1L));
        assertThat(deviceRepository.findByDeviceId("mqtt-store")).isPresent();
    }

    @Test
    void 검증_실패_봉투는_드롭되고_파이프라인은_유지된다() throws Exception {
        // deviceId 공백 → @NotBlank 위반 → 핸들러가 드롭. 이어 정상 sentinel로 파이프라인 생존 확인.
        publishRepeated(payload("", Instant.now().minusSeconds(6).toString()), 3);
        publishRepeated(payload("mqtt-sentinel", Instant.now().minusSeconds(5).toString()), 5);

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () ->
                                assertThat(deviceRepository.findByDeviceId("mqtt-sentinel"))
                                        .isPresent());
        // 검증 실패분은 저장 안 됨 → sentinel 1행만.
        assertThat(telemetryRepository.count()).isEqualTo(1L);
        assertThat(deviceRepository.findByDeviceId("")).isEmpty();
    }

    private void publishRepeated(String payload, int times) throws Exception {
        IMqttClient pub =
                new MqttClient(
                        brokerUrl(), "test-pub-" + UUID.randomUUID(), new MemoryPersistence());
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        pub.connect(opts);
        try {
            for (int i = 0; i < times; i++) {
                MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
                message.setQos(1);
                pub.publish(TOPIC, message);
                Thread.sleep(150);
            }
        } finally {
            pub.disconnect();
            pub.close();
        }
    }

    private String payload(String deviceId, String timestamp) {
        return """
        {
          "deviceId":"%s","deviceType":"PHONE","timestamp":"%s",
          "location":{"lat":37.0,"lng":127.0,"accuracy":5.0,"speed":1.0,"heading":90.0},
          "battery":{"level":80,"charging":false},
          "network":{"type":"CELLULAR","online":true},
          "activity":"WALKING","appState":"FOREGROUND","permission":"WHILE_IN_USE","sharingEnabled":true
        }"""
                .formatted(deviceId, timestamp);
    }
}
