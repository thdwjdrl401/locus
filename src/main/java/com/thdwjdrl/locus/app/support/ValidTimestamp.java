package com.thdwjdrl.locus.app.support;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 타임스탬프 정합성 검증 — 미래(now+maxFutureSeconds 초과) 또는 과도한 과거(now-maxPastDays 이전)를 거부.
 *
 * <p>네트워크 지연·시계 오차를 흡수할 정도의 윈도우를 둔다(기본 미래 60s, 과거 7d). 클라이언트 버퍼링 backfill을 허용하려면 maxPastDays를 늘린다.
 */
@Target({
    ElementType.FIELD,
    ElementType.PARAMETER,
    ElementType.METHOD,
    ElementType.RECORD_COMPONENT,
    ElementType.ANNOTATION_TYPE
})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = TimestampValidator.class)
public @interface ValidTimestamp {
    String message() default "timestamp가 허용 범위를 벗어났습니다 (미래 또는 과도한 과거)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    long maxFutureSeconds() default 60;

    long maxPastDays() default 7;
}
