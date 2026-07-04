package com.thdwjdrl.locus.app.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MQTT 토픽·페이로드 → {@link TelemetryRequest} 역직렬화·검증·적재 (M-MQTT).
 *
 * <p>deviceId는 <b>토픽이 기준</b>이다({@code {prefix}/{deviceId}}) — identity를 토픽에 두는 것이 MQTT 관례고(브로커
 * ACL·last-will의 기반, 페이로드 id는 브로커가 강제 못 함), 페이로드에 deviceId가 있으면 토픽과 일치해야 하며(불일치 drop) 없으면 토픽 값으로
 * 채운다. 전송(구독)과 분리된 순수 로직 — {@link MqttSubscriber} 구현체가 메시지마다 {@link #handle(String, byte[])}를 호출한다.
 * HTTP는 {@code @Valid} 실패 시 400을 돌려주지만 MQTT는 응답 대상이 없어 <b>로그·드롭</b>한다(후속 DLQ 여지). 통과분은 HTTP와 같은
 * {@link TelemetryIngestService}로 합류해 하류(조립·타입검증·포트·stream fan-out)를 공유한다.
 */
@Component
@ConditionalOnProperty(name = "locus.mqtt.enabled", havingValue = "true")
public class MqttTelemetryHandler {

    private static final Logger log = LoggerFactory.getLogger(MqttTelemetryHandler.class);

    private final ObjectMapper json;
    private final Validator validator;
    private final TelemetryIngestService ingest;
    private final Counter received;
    private final Counter dropped;

    public MqttTelemetryHandler(
            ObjectMapper json,
            Validator validator,
            TelemetryIngestService ingest,
            MeterRegistry meters) {
        this.json = json;
        this.validator = validator;
        this.ingest = ingest;
        // 측정 오라클(M-MQTT): accepted = received − dropped == DB count. HTTP의 202 카운트에 대응.
        this.received =
                Counter.builder("locus.mqtt.received")
                        .description("MQTT로 수신한 텔레메트리 수(드롭 포함)")
                        .register(meters);
        this.dropped =
                Counter.builder("locus.mqtt.dropped")
                        .description("역직렬화·검증·적재 실패로 드롭한 수")
                        .register(meters);
    }

    /** 한 MQTT 메시지 처리. 역직렬화·id 정합·검증·적재 각 단계 실패는 그 메시지만 드롭(브로커·다른 메시지에 영향 없음). */
    public void handle(String topic, byte[] payload) {
        received.increment();
        String topicDeviceId = topic.substring(topic.lastIndexOf('/') + 1);
        TelemetryRequest request;
        try {
            request = json.readValue(payload, TelemetryRequest.class);
        } catch (Exception e) {
            dropped.increment();
            log.warn("MQTT 페이로드 역직렬화 실패 드롭: {}", e.toString());
            return;
        }

        if (request.deviceId() == null || request.deviceId().isBlank()) {
            request = withDeviceId(request, topicDeviceId);
        } else if (!request.deviceId().equals(topicDeviceId)) {
            dropped.increment();
            log.warn(
                    "MQTT 토픽·페이로드 deviceId 불일치 드롭(topic={}, payload={})",
                    topicDeviceId,
                    request.deviceId());
            return;
        }

        Set<ConstraintViolation<TelemetryRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            dropped.increment();
            log.warn("MQTT 봉투 검증 실패 드롭(device={}): {}", request.deviceId(), violations);
            return;
        }

        try {
            ingest.ingest(request);
        } catch (RuntimeException e) {
            dropped.increment();
            log.warn("MQTT 적재 실패 드롭(device={}): {}", request.deviceId(), e.toString());
        }
    }

    /** 토픽에서 온 deviceId로 봉투 재구성(페이로드에 deviceId가 없을 때). */
    private TelemetryRequest withDeviceId(TelemetryRequest r, String deviceId) {
        return new TelemetryRequest(
                deviceId, r.deviceType(), r.timestamp(), r.location(), r.metrics());
    }
}
