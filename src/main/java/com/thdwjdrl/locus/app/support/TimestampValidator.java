package com.thdwjdrl.locus.app.support;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** {@link ValidTimestamp} 검증기. 테스트는 고정 {@link Clock}을 주입해 경계를 검증할 수 있다. */
public class TimestampValidator implements ConstraintValidator<ValidTimestamp, Instant> {

    private long maxFutureSeconds;
    private long maxPastDays;
    private final Clock clock;

    public TimestampValidator() {
        this(Clock.systemUTC());
    }

    TimestampValidator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void initialize(ValidTimestamp annotation) {
        this.maxFutureSeconds = annotation.maxFutureSeconds();
        this.maxPastDays = annotation.maxPastDays();
    }

    @Override
    public boolean isValid(Instant value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // null은 @NotNull이 담당
        }
        Instant now = clock.instant();
        if (value.isAfter(now.plusSeconds(maxFutureSeconds))) {
            return false;
        }
        return !value.isBefore(now.minus(maxPastDays, ChronoUnit.DAYS));
    }
}
