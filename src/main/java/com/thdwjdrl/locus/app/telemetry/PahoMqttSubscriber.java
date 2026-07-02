package com.thdwjdrl.locus.app.telemetry;

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
 * <p>{@code messageArrived}는 Paho 단일 콜백 스레드에서 실행된다 — 고처리량에서 적재가 무거워지면 여기서 익스큐터로 오프로드해야 한다(측정 포인트).
 * 그 한계가 드러나면 backpressure 내장 HiveMQ 구현으로 전환(이음새라 어댑터만 교체).
 */
@Component
@ConditionalOnProperty(name = "locus.mqtt.enabled", havingValue = "true")
public class PahoMqttSubscriber implements MqttSubscriber {

    private static final Logger log = LoggerFactory.getLogger(PahoMqttSubscriber.class);

    private final MqttIngestProperties props;
    private final MqttTelemetryHandler handler;

    private volatile IMqttClient client;
    private volatile boolean running = false;

    public PahoMqttSubscriber(MqttIngestProperties props, MqttTelemetryHandler handler) {
        this.props = props;
        this.handler = handler;
    }

    @Override
    public void start() {
        try {
            client = new MqttClient(props.getUrl(), props.getClientId(), new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            client.setCallback(
                    new MqttCallbackExtended() {
                        @Override
                        public void connectComplete(boolean reconnect, String serverUri) {
                            try {
                                client.subscribe(props.getTopic(), props.getQos());
                                log.info(
                                        "MQTT 구독 (topic={}, qos={}, reconnect={})",
                                        props.getTopic(),
                                        props.getQos(),
                                        reconnect);
                            } catch (MqttException e) {
                                log.error("MQTT 구독 실패 (topic={})", props.getTopic(), e);
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
                            handler.handle(message.getPayload());
                        }

                        @Override
                        public void deliveryComplete(IMqttDeliveryToken token) {}
                    });
            client.connect(options);
            running = true;
            log.info("MQTT 구독자 시작 (url={}, clientId={})", props.getUrl(), props.getClientId());
        } catch (MqttException e) {
            throw new IllegalStateException("MQTT 연결 실패: " + props.getUrl(), e);
        }
    }

    @Override
    public void stop() {
        running = false;
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
