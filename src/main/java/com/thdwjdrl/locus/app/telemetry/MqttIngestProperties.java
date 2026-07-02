package com.thdwjdrl.locus.app.telemetry;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MQTT 수집 경로 설정 ({@code locus.mqtt.*}, M-MQTT).
 *
 * <p>{@code enabled=false}(기본)면 구독자·핸들러 빈이 생성되지 않아 HTTP-only 실행·테스트는 브로커가 필요 없다. 활성 시 {@link
 * PahoMqttSubscriber}가 {@code url}의 브로커에서 {@code topic}을 {@code qos}로 구독한다.
 */
@Component
@ConfigurationProperties(prefix = "locus.mqtt")
public class MqttIngestProperties {

    /** MQTT 수집 경로 활성 여부. 기본 꺼둠(HTTP 수집만). */
    private boolean enabled = false;

    /** 브로커 URL(tcp://host:port). */
    private String url = "tcp://localhost:1883";

    /** 구독 토픽. 페이로드는 HTTP와 같은 TelemetryRequest JSON 봉투(deviceId 포함). */
    private String topic = "telemetry";

    /** 구독 QoS. 1=at-least-once(중복은 {@code ON CONFLICT} dedup) · 0=fire-forget. 측정 변수. */
    private int qos = 1;

    /** 브로커 클라이언트 식별자. */
    private String clientId = "locus-ingest";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public int getQos() {
        return qos;
    }

    public void setQos(int qos) {
        this.qos = qos;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }
}
