package com.thdwjdrl.locus.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.thdwjdrl.locus.IntegrationTestBase;
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
 * M-MQTT 런2(2번째 슬라이스) — 다중 연결 + shared subscription e2e.
 *
 * <p>핵심 검증: {@code connections=2}일 때 두 연결이 {@code $share/{group}/telemetry/+}로 구독하면 브로커가 메시지를
 * <b>분배</b>(각 메시지 한 연결로만)하는가. 각 디바이스를 R회 발행 → {@code locus.mqtt.received} 증가분이 <b>R×N</b>이어야
 * 한다(분배됨). 만약 shared가 안 먹어 두 연결이 각자 전량 받으면 R×N×2가 된다. + 전량 적재(DB=N)로 오프로드·매뉴얼 ack도 확인.
 */
@TestPropertySource(
        properties = {
            "locus.mqtt.enabled=true",
            "locus.mqtt.connections=2",
            "locus.mqtt.worker-threads=2",
            "locus.ingest.batch-size=100",
            "locus.ingest.max-delay-ms=50"
        })
class MqttSharedSubscriptionIntegrationTest extends IntegrationTestBase {

    private static final String TOPIC_PREFIX = "telemetry";
    private static final int DEVICE_COUNT = 20;
    private static final int REPEATS = 3;

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
        registry.add("locus.mqtt.url", MqttSharedSubscriptionIntegrationTest::brokerUrl);
    }

    private static String brokerUrl() {
        return "tcp://" + MOSQUITTO.getHost() + ":" + MOSQUITTO.getMappedPort(1883);
    }

    @Autowired private TelemetryRepository telemetryRepository;
    @Autowired private MeterRegistry meters;

    @BeforeEach
    void clean() {
        telemetryRepository.deleteAll();
    }

    @Test
    void shared_subscription_두_연결이_메시지를_분배한다() throws Exception {
        double receivedBefore = meters.get("locus.mqtt.received").counter().count();
        String ts = Instant.now().minusSeconds(5).toString();

        IMqttClient pub =
                new MqttClient(
                        brokerUrl(), "share-pub-" + UUID.randomUUID(), new MemoryPersistence());
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        pub.connect(opts);
        try {
            Thread.sleep(500); // 두 구독 연결이 붙고 shared 구독을 마칠 시간
            for (int r = 0; r < REPEATS; r++) {
                for (int i = 0; i < DEVICE_COUNT; i++) {
                    String deviceId = "share-" + i;
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

        // 전량 적재: N개 디바이스 = N행(반복은 ON CONFLICT).
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () ->
                                assertThat(telemetryRepository.count())
                                        .isEqualTo((long) DEVICE_COUNT));

        // 분배 검증: 두 연결이 받은 총량이 R×N 근처(중복 없음)이고 R×N×2(두 연결 각자 전량)가 아니어야 한다.
        // exact == R×N은 QoS1 재전송에 취약 → "적어도 전량, 두 배 미만" 범위로 shared 작동을 판별.
        double delta = meters.get("locus.mqtt.received").counter().count() - receivedBefore;
        assertThat(delta)
                .isGreaterThanOrEqualTo(REPEATS * DEVICE_COUNT)
                .isLessThan((double) (REPEATS * DEVICE_COUNT * 2));
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
