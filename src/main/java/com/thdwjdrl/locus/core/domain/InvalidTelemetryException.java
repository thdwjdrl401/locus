package com.thdwjdrl.locus.core.domain;

/** 타입별 의미 검증(전략) 실패. 형식·범위는 DTO Bean Validation이, 이건 도메인 규칙 위반을 나타낸다. */
public class InvalidTelemetryException extends RuntimeException {

    public InvalidTelemetryException(String message) {
        super(message);
    }
}
