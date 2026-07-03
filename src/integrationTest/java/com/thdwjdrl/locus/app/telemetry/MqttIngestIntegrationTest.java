package com.thdwjdrl.locus.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.thdwjdrl.locus.IntegrationTestBase;
import com.thdwjdrl.locus.app.device.DeviceRepository;
import io.micrometer.core.instrument.MeterRegistry;
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
 * <p>PG는 {@link IntegrationTestBase}에서 상속, Mosquitto는 이 클래스가 자체 컨테이너로 붙인다. 토픽 계약: {@code
 * telemetry/{deviceId}}(identity는 토픽에, 구독은 {@code telemetry/+}). 검증: (1) {@code enabled=true} 컨텍스트가
 * 뜨고 {@link PahoMqttSubscriber}가 구독 연결, (2) MQTT 발행 → HTTP와 같은 {@link TelemetryIngestService}로 합류해
 * DB 적재, (3) deviceId 없는 페이로드는 토픽 값으로 적재, (4) 토픽·페이로드 불일치는 드롭.
 */
@TestPropertySource(
        properties = {
            "locus.mqtt.enabled=true",
            "locus.ingest.batch-size=100",
            "locus.ingest.max-delay-ms=50"
        })
class MqttIngestIntegrationTest extends IntegrationTestBase {

    private static final String TOPIC_PREFIX = "telemetry";

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
    @Autowired private MeterRegistry meters; // 측정 오라클 카운터(received/dropped) 검증

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
        double receivedBefore = meters.get("locus.mqtt.received").counter().count();
        String ts = Instant.now().minusSeconds(5).toString();
        // 구독 준비 레이스 회피: 멱등 페이로드(동일 device+ts)를 여러 번 발행 → ON CONFLICT로 최종 1행.
        publishRepeated("mqtt-store", payload("mqtt-store", ts), 5);

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(telemetryRepository.count()).isEqualTo(1L));
        assertThat(deviceRepository.findByDeviceId("mqtt-store")).isPresent();
        // 수신 카운터가 오라클로 동작하는지(구독 레이스로 5 미만일 수 있어 하한만).
        assertThat(meters.get("locus.mqtt.received").counter().count())
                .isGreaterThan(receivedBefore);
    }

    @Test
    void deviceId_없는_페이로드는_토픽_값으로_적재된다() throws Exception {
        // 토픽이 기준 — 페이로드에 deviceId가 없으면 토픽 마지막 세그먼트로 채운다(emqtt-bench 템플릿 경로).
        publishRepeated(
                "mqtt-topic-only",
                payloadWithoutDeviceId(Instant.now().minusSeconds(5).toString()),
                5);

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () ->
                                assertThat(deviceRepository.findByDeviceId("mqtt-topic-only"))
                                        .isPresent());
        assertThat(telemetryRepository.count()).isEqualTo(1L);
    }

    @Test
    void 토픽과_페이로드_deviceId가_다르면_드롭된다() throws Exception {
        double droppedBefore = meters.get("locus.mqtt.dropped").counter().count();
        // 스푸핑 방지: 토픽(mqtt-a)과 페이로드(mqtt-b)가 다르면 저장하지 않는다.
        publishRepeated("mqtt-a", payload("mqtt-b", Instant.now().minusSeconds(5).toString()), 3);

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () ->
                                assertThat(meters.get("locus.mqtt.dropped").counter().count())
                                        .isGreaterThan(droppedBefore));
        assertThat(telemetryRepository.count()).isZero();
        assertThat(deviceRepository.findByDeviceId("mqtt-a")).isEmpty();
        assertThat(deviceRepository.findByDeviceId("mqtt-b")).isEmpty();
    }

    private void publishRepeated(String deviceId, String payload, int times) throws Exception {
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
                pub.publish(TOPIC_PREFIX + "/" + deviceId, message);
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

    private String payloadWithoutDeviceId(String timestamp) {
        return """
        {
          "deviceType":"PHONE","timestamp":"%s",
          "location":{"lat":37.0,"lng":127.0,"accuracy":5.0,"speed":1.0,"heading":90.0},
          "battery":{"level":80,"charging":false},
          "network":{"type":"CELLULAR","online":true},
          "activity":"WALKING","appState":"FOREGROUND","permission":"WHILE_IN_USE","sharingEnabled":true
        }"""
                .formatted(timestamp);
    }
}
