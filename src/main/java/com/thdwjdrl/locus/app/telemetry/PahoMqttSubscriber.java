package com.thdwjdrl.locus.app.telemetry;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link MqttSubscriber}의 Eclipse Paho v3 구현 (M-MQTT).
 *
 * <p>connect·subscribe·재접속을 직접 쥔다 — MQTT의 간헐연결 전제에 맞춰 {@code automaticReconnect}로 끊기면 재접속하고, {@code
 * connectComplete}에서 <b>재접속 후에도 재구독</b>한다. 받은 메시지는 {@link MqttTelemetryHandler}로 넘긴다.
 *
 * <p><b>인입 병렬화(측정 M-MQTT.md)</b>: {@code messageArrived}는 Paho 단일 콜백 스레드에서 실행된다 — 인라인 처리는 인입을
 * ~3.25K/s로 묶는다(같은 스토리지가 HTTP로는 ~9.7K). {@code worker-threads>0}이면 역직렬화·검증·XADD를 바운드 익스큐터로 오프로드하고,
 * <b>매뉴얼 ack</b>로 처리 성공 후에만 {@code messageArrivedComplete}를 호출한다(크래시 시 미ack분은 브로커가 재전송 =
 * at-least-once 유지, 스토리지 XACK 복구와 같은 원리). 큐가 차면 {@code CallerRuns}로 Paho 스레드가 직접 실행해 백프레셔를 건다(무손실).
 * {@code worker-threads=0}(기본)이면 인라인·auto-ack(현행 동작 보존, before/after 토글).
 */
@Component
@ConditionalOnProperty(name = "locus.mqtt.enabled", havingValue = "true")
public class PahoMqttSubscriber implements MqttSubscriber {

    private static final Logger log = LoggerFactory.getLogger(PahoMqttSubscriber.class);

    private final MqttIngestProperties props;
    private final MqttTelemetryHandler handler;

    private volatile IMqttClient client;
    private volatile ThreadPoolExecutor executor; // null = 인라인 모드
    private volatile boolean running = false;

    public PahoMqttSubscriber(MqttIngestProperties props, MqttTelemetryHandler handler) {
        this.props = props;
        this.handler = handler;
    }

    @Override
    public void start() {
        try {
            client = new MqttClient(props.getUrl(), props.getClientId(), new MemoryPersistence());
            if (props.getWorkerThreads() > 0) {
                // 오프로드 모드: 매뉴얼 ack로 처리 성공 후에만 ack(at-least-once). connect 전에 설정.
                executor = newExecutor(props.getWorkerThreads(), props.getQueueCapacity());
                client.setManualAcks(true);
            }
            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            client.setCallback(
                    new MqttCallbackExtended() {
                        @Override
                        public void connectComplete(boolean reconnect, String serverUri) {
                            // identity는 토픽에({prefix}/{deviceId}) — 단일 레벨 와일드카드로 전 디바이스 구독.
                            String filter = props.getTopic() + "/+";
                            try {
                                client.subscribe(filter, props.getQos());
                                log.info(
                                        "MQTT 구독 (filter={}, qos={}, reconnect={}, workers={})",
                                        filter,
                                        props.getQos(),
                                        reconnect,
                                        props.getWorkerThreads());
                            } catch (MqttException e) {
                                log.error("MQTT 구독 실패 (filter={})", filter, e);
                            }
                        }

                        @Override
                        public void connectionLost(Throwable cause) {
                            log.warn(
                                    "MQTT 연결 끊김(자동 재접속): {}",
                                    cause == null ? "unknown" : cause.toString());
                        }

                        @Override
                        public void messageArrived(String topic, MqttMessage message) {
                            if (executor == null) {
                                handler.handle(topic, message.getPayload()); // 인라인·auto-ack
                                return;
                            }
                            // 오프로드: 처리 후 매뉴얼 ack. payload를 미리 꺼내 태스크에 넘긴다.
                            int id = message.getId();
                            int qos = message.getQos();
                            byte[] payload = message.getPayload();
                            executor.execute(
                                    () -> {
                                        try {
                                            handler.handle(topic, payload);
                                        } finally {
                                            // 처리 실패(드롭)도 ack — 포이즌 메시지 무한 재전송 방지. 크래시(미ack)만 재전송.
                                            ack(id, qos);
                                        }
                                    });
                        }

                        @Override
                        public void deliveryComplete(IMqttDeliveryToken token) {}
                    });
            client.connect(options);
            running = true;
            log.info(
                    "MQTT 구독자 시작 (url={}, clientId={}, workers={})",
                    props.getUrl(),
                    props.getClientId(),
                    props.getWorkerThreads());
        } catch (MqttException e) {
            throw new IllegalStateException("MQTT 연결 실패: " + props.getUrl(), e);
        }
    }

    /** 처리 성공/드롭 후 매뉴얼 ack. ack 실패(예: 종료 중 연결 끊김)는 미ack로 남아 재전송 = at-least-once. */
    private void ack(int messageId, int qos) {
        try {
            client.messageArrivedComplete(messageId, qos);
        } catch (MqttException e) {
            log.warn("MQTT 매뉴얼 ack 실패 (id={}): {}", messageId, e.toString());
        }
    }

    private ThreadPoolExecutor newExecutor(int threads, int queueCapacity) {
        AtomicInteger seq = new AtomicInteger();
        return new ThreadPoolExecutor(
                threads,
                threads,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                r -> {
                    Thread t = new Thread(r, "mqtt-ingest-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Override
    public void stop() {
        running = false;
        ThreadPoolExecutor exec = this.executor;
        if (exec != null) {
            // 연결 유지한 채 큐 드레인 → in-flight ack 성공률↑. 남으면 재전송으로 커버.
            exec.shutdown();
            try {
                if (!exec.awaitTermination(10, TimeUnit.SECONDS)) {
                    exec.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exec.shutdownNow();
            }
        }
        if (client == null) {
            return;
        }
        try {
            client.disconnect();
            client.close();
        } catch (MqttException e) {
            log.warn("MQTT 종료 실패: {}", e.toString());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
