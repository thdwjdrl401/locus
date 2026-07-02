package com.thdwjdrl.locus.app.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MQTT 페이로드 → {@link TelemetryRequest} 역직렬화·검증·적재 (M-MQTT).
 *
 * <p>전송(구독)과 분리된 순수 로직 — {@link MqttSubscriber} 구현체가 메시지마다 {@link #handle(byte[])}를 호출한다. HTTP는
 * {@code @Valid} 실패 시 400을 돌려주지만 MQTT는 응답 대상이 없어 <b>로그·드롭</b>한다(후속 DLQ 여지). 통과분은 HTTP와 같은 {@link
 * TelemetryIngestService}로 합류해 하류(조립·타입검증·포트·stream fan-out)를 공유한다.
 */
@Component
@ConditionalOnProperty(name = "locus.mqtt.enabled", havingValue = "true")
public class MqttTelemetryHandler {

    private static final Logger log = LoggerFactory.getLogger(MqttTelemetryHandler.class);

    private final ObjectMapper json;
    private final Validator validator;
    private final TelemetryIngestService ingest;

    public MqttTelemetryHandler(
            ObjectMapper json, Validator validator, TelemetryIngestService ingest) {
        this.json = json;
        this.validator = validator;
        this.ingest = ingest;
    }

    /** 한 MQTT 메시지 처리. 역직렬화·검증·적재 각 단계 실패는 그 메시지만 드롭(브로커·다른 메시지에 영향 없음). */
    public void handle(byte[] payload) {
        TelemetryRequest request;
        try {
            request = json.readValue(payload, TelemetryRequest.class);
        } catch (Exception e) {
            log.warn("MQTT 페이로드 역직렬화 실패 드롭: {}", e.toString());
            return;
        }

        Set<ConstraintViolation<TelemetryRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            log.warn("MQTT 봉투 검증 실패 드롭(device={}): {}", request.deviceId(), violations);
            return;
        }

        try {
            ingest.ingest(request);
        } catch (RuntimeException e) {
            log.warn("MQTT 적재 실패 드롭(device={}): {}", request.deviceId(), e.toString());
        }
    }
}
