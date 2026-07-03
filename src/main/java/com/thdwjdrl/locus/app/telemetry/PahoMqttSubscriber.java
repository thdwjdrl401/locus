package com.thdwjdrl.locus.app.telemetry;

import java.util.ArrayList;
import java.util.List;
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
 * <p><b>인입 병렬화(측정 M-MQTT.md 런2)</b>: 두 축으로 병렬화한다.
 *
 * <ul>
 *   <li><b>처리</b>({@code worker-threads>0}): 역직렬화·검증·XADD를 바운드 익스큐터로 오프로드. <b>매뉴얼 ack</b>로 처리 성공
 *       후에만 {@code messageArrivedComplete}(크래시 시 미ack분은 브로커 재전송 = at-least-once). 큐가 차면 {@code
 *       CallerRuns}로 백프레셔(무손실). 0(기본)이면 인라인·auto-ack(현행 보존).
 *   <li><b>수신</b>({@code connections>1}): N개 Paho 클라이언트가 <b>shared subscription</b>({@code
 *       $share/{group}/{prefix}/+})으로 구독 → 브로커가 연결들에 메시지를 분배(중복 없음). 단일 연결의 QoS1 인플라이트 창·수신 스레드가
 *       파이프 폭을 제한하던 병목을 넓힌다. 1(기본)이면 plain {@code {prefix}/+} 단일 연결(현행 보존).
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "locus.mqtt.enabled", havingValue = "true")
public class PahoMqttSubscriber implements MqttSubscriber {

    private static final Logger log = LoggerFactory.getLogger(PahoMqttSubscriber.class);

    private final MqttIngestProperties props;
    private final MqttTelemetryHandler handler;

    private final List<IMqttClient> clients = new ArrayList<>();
    private volatile ThreadPoolExecutor executor; // null = 인라인 모드
    private volatile boolean running = false;

    public PahoMqttSubscriber(MqttIngestProperties props, MqttTelemetryHandler handler) {
        this.props = props;
        this.handler = handler;
    }

    @Override
    public void start() {
        int conns = Math.max(1, props.getConnections());
        if (props.getWorkerThreads() > 0) {
            executor = newExecutor(props.getWorkerThreads(), props.getQueueCapacity());
        }
        try {
            for (int i = 0; i < conns; i++) {
                clients.add(connectOne(i, conns));
            }
            running = true;
            log.info(
                    "MQTT 구독자 시작 (url={}, connections={}, workers={})",
                    props.getUrl(),
                    conns,
                    props.getWorkerThreads());
        } catch (MqttException e) {
            throw new IllegalStateException("MQTT 연결 실패: " + props.getUrl(), e);
        }
    }

    /** 연결 하나 생성 — 콜백은 자기 클라이언트({@code self})를 캡처해 구독·ack를 그 연결로 수행. */
    private IMqttClient connectOne(int index, int total) throws MqttException {
        String clientId = total > 1 ? props.getClientId() + "-" + index : props.getClientId();
        IMqttClient self = new MqttClient(props.getUrl(), clientId, new MemoryPersistence());
        if (executor != null) {
            self.setManualAcks(true); // connect 전에 설정
        }
        // connections>1이면 shared subscription으로 분배, 아니면 현행 plain 필터.
        String filter =
                total > 1
                        ? "$share/" + props.getShareGroup() + "/" + props.getTopic() + "/+"
                        : props.getTopic() + "/+";
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        self.setCallback(
                new MqttCallbackExtended() {
                    @Override
                    public void connectComplete(boolean reconnect, String serverUri) {
                        try {
                            self.subscribe(filter, props.getQos()); // 재접속 후에도 재구독
                            log.info(
                                    "MQTT 구독 (id={}, filter={}, qos={}, reconnect={})",
                                    clientId,
                                    filter,
                                    props.getQos(),
                                    reconnect);
                        } catch (MqttException e) {
                            log.error("MQTT 구독 실패 (filter={})", filter, e);
                        }
                    }

                    @Override
                    public void connectionLost(Throwable cause) {
                        log.warn(
                                "MQTT 연결 끊김(자동 재접속, id={}): {}",
                                clientId,
                                cause == null ? "unknown" : cause.toString());
                    }

                    @Override
                    public void messageArrived(String topic, MqttMessage message) {
                        if (executor == null) {
                            handler.handle(topic, message.getPayload()); // 인라인·auto-ack
                            return;
                        }
                        int id = message.getId();
                        int qos = message.getQos();
                        byte[] payload = message.getPayload();
                        executor.execute(
                                () -> {
                                    try {
                                        handler.handle(topic, payload);
                                    } finally {
                                        // 드롭도 ack(포이즌 무한 재전송 방지). 크래시(미ack)만 재전송. ack는 도착한 그 연결로.
                                        ack(self, id, qos);
                                    }
                                });
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken token) {}
                });
        self.connect(options);
        return self;
    }

    /** 처리 성공/드롭 후 매뉴얼 ack. ack 실패(예: 종료 중 연결 끊김)는 미ack로 남아 재전송 = at-least-once. */
    private void ack(IMqttClient client, int messageId, int qos) {
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
        for (IMqttClient c : clients) {
            try {
                c.disconnect();
                c.close();
            } catch (MqttException e) {
                log.warn("MQTT 종료 실패: {}", e.toString());
            }
        }
        clients.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
