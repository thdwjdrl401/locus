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

    /**
     * 토픽 접두(prefix). 디바이스는 {@code {topic}/{deviceId}}로 발행하고 구독 필터는 {@code {topic}/+} — identity는
     * 토픽에(브로커 ACL·last-will 기반). 페이로드는 HTTP와 같은 TelemetryRequest JSON 봉투(deviceId 생략 가능, 있으면 토픽과
     * 일치해야 함).
     */
    private String topic = "telemetry";

    /** 구독 QoS. 1=at-least-once(중복은 {@code ON CONFLICT} dedup) · 0=fire-forget. 측정 변수. */
    private int qos = 1;

    /** 브로커 클라이언트 식별자. */
    private String clientId = "locus-ingest";

    /**
     * 인입 처리 워커 스레드 수. 0=인라인(Paho 단일 콜백 스레드에서 직접 처리, 현행). N>0이면 매뉴얼 ack + 바운드 익스큐터로 오프로드해
     * 역직렬화·검증·XADD를 병렬화(측정: 단일 콜백이 인입을 묶는 병목, M-MQTT.md). ack는 처리 성공 후에만 → at-least-once 유지.
     * before/after 토글이라 기본 0.
     */
    private int workerThreads = 0;

    /** 오프로드 큐 용량(worker-threads>0일 때). 가득 차면 CallerRuns로 Paho 스레드가 직접 실행(백프레셔, 무손실). */
    private int queueCapacity = 10000;

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

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }
}
