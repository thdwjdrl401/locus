package com.thdwjdrl.locus.app.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.thdwjdrl.locus.app.telemetry.TelemetryRequest;
import com.thdwjdrl.locus.core.domain.DeviceType;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 시뮬레이터가 실제로 검증을 통과하는 봉투를 만드는지 + 상태가 진전되는지. */
class PhoneProfileTest {

    private static ValidatorFactory factory;
    private static Validator validator;
    private final PhoneProfile profile = new PhoneProfile();

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void 담당_타입은_PHONE() {
        assertThat(profile.deviceType()).isEqualTo(DeviceType.PHONE);
    }

    @Test
    void 생성된_봉투는_항상_검증을_통과한다() {
        SimState state = new SimState("phone-0001", 37.5, 127.0);
        for (int i = 0; i < 500; i++) {
            TelemetryRequest envelope = profile.step(state);
            assertThat(validator.validate(envelope)).as("frame %d", i).isEmpty();
        }
    }

    @Test
    void 반복하면_상태가_진전된다() {
        SimState state = new SimState("phone-0001", 37.5, 127.0);
        for (int i = 0; i < 100; i++) {
            profile.step(state);
        }
        assertThat(state.tick).isEqualTo(100);
        assertThat(state.batteryLevel).isLessThan(100); // 100프레임이면 배터리 감소
        assertThat(state.lat).isNotEqualTo(37.5); // random walk로 이동
    }
}
