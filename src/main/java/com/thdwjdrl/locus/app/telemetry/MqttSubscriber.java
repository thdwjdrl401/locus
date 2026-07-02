package com.thdwjdrl.locus.app.telemetry;

import org.springframework.context.SmartLifecycle;

/**
 * MQTT 수집 구독 이음새 (M-MQTT, ADR 0004 — "구현을 갈아끼운다고 계획서가 명시한 이음새").
 *
 * <p>브로커를 구독해 받은 페이로드를 {@link MqttTelemetryHandler}로 흘린다. 구현체는 전송 라이브러리만 다르다: 현재 {@link
 * PahoMqttSubscriber}(Eclipse Paho v3). 고처리량에서 리액티브·MQTT5·backpressure가 필요해지면 HiveMQ 구현을 <b>추가</b>해
 * 전환한다(재작성 아님, 측정 게이트). {@link SmartLifecycle}이라 Spring이 기동/종료를 관리한다.
 */
public interface MqttSubscriber extends SmartLifecycle {}
