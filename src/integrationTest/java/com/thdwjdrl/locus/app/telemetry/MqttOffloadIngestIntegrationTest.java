package com.thdwjdrl.locus.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.thdwjdrl.locus.IntegrationTestBase;
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
 * M-MQTT 런2 — 인입 오프로드 모드({@code worker-threads>0}) e2e.
 *
 * <p>인라인 경로는 {@link MqttIngestIntegrationTest}가 검증한다. 여기선 <b>매뉴얼 ack + 익스큐터 오프로드</b>가 여러 메시지를 병렬
 * 처리하고 처리 후 ack해 <b>전량 적재</b>되는지 확인한다(오프로드가 손실·무한 재전송 없이 동작). 워커 4로 서로 다른 디바이스 다수를 발행 → 모두 DB에 남아야
 * 한다.
 */
@TestPropertySource(
        properties = {
            "locus.mqtt.enabled=true",
            "locus.mqtt.worker-threads=4",
            "locus.ingest.batch-size=100",
            "locus.ingest.max-delay-ms=50"
        })
class MqttOffloadIngestIntegrationTest extends IntegrationTestBase {

    private static final String TOPIC_PREFIX = "telemetry";
    private static final int DEVICE_COUNT = 20;

    static final GenericContainer<?> MOSQUITTO =
            new GenericContainer<>(DockerImageName.parse("eclipse-mosquitto:2.0"))
                    .withExposedPorts(1883)
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
        registry.add("locus.mqtt.url", MqttOffloadIngestIntegrationTest::brokerUrl);
    }

    private static String brokerUrl() {
        return "tcp://" + MOSQUITTO.getHost() + ":" + MOSQUITTO.getMappedPort(1883);
    }

    @Autowired private TelemetryRepository telemetryRepository;
    @Autowired private MqttSubscriber subscriber;

    @BeforeEach
    void clean() {
        telemetryRepository.deleteAll();
    }

    @Test
    void 오프로드_워커가_여러_디바이스를_병렬_처리해_전량_적재된다() throws Exception {
        assertThat(subscriber).isInstanceOf(PahoMqttSubscriber.class);
        String ts = Instant.now().minusSeconds(5).toString();

        IMqttClient pub =
                new MqttClient(
                        brokerUrl(), "off-pub-" + UUID.randomUUID(), new MemoryPersistence());
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        pub.connect(opts);
        try {
            // 구독 준비 레이스 회피: 디바이스별 동일 페이로드를 3회 발행 → ON CONFLICT로 디바이스당 1행.
            for (int r = 0; r < 3; r++) {
                for (int i = 0; i < DEVICE_COUNT; i++) {
                    String deviceId = "off-" + i;
                    MqttMessage message =
                            new MqttMessage(payload(deviceId, ts).getBytes(StandardCharsets.UTF_8));
                    message.setQos(1);
                    pub.publish(TOPIC_PREFIX + "/" + deviceId, message);
                }
                Thread.sleep(100);
            }
        } finally {
            pub.disconnect();
            pub.close();
        }

        // 오프로드+매뉴얼 ack가 전량 처리 → 20개 디바이스 = 20행.
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () ->
                                assertThat(telemetryRepository.count())
                                        .isEqualTo((long) DEVICE_COUNT));
    }

    private String payload(String deviceId, String timestamp) {
        return """
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
    }
}
