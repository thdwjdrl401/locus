package com.thdwjdrl.locus.app.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.thdwjdrl.locus.app.device.AmrHandler;
import com.thdwjdrl.locus.app.telemetry.TelemetryRequest;
import com.thdwjdrl.locus.core.domain.DeviceType;
import com.thdwjdrl.locus.core.domain.Telemetry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** AMR 시뮬레이터가 검증(봉투 + 핸들러)을 통과하는 봉투를 만들고 상태가 진전되는지. */
class AmrProfileTest {

    private static ValidatorFactory factory;
    private static Validator validator;
    private final AmrProfile profile = new AmrProfile();
    private final AmrHandler handler = new AmrHandler();

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
    void 담당_타입은_AMR() {
        assertThat(profile.deviceType()).isEqualTo(DeviceType.AMR);
    }

    @Test
    void 생성된_봉투는_항상_봉투검증과_핸들러검증을_통과한다() {
        SimState state = new SimState("amr-0001", 37.5, 127.0);
        for (int i = 0; i < 2000; i++) {
            TelemetryRequest envelope = profile.step(state);
            assertThat(validator.validate(envelope)).as("frame %d 봉투", i).isEmpty();
            Telemetry t =
                    new Telemetry(
                            envelope.deviceId(),
                            envelope.deviceType(),
                            envelope.timestamp(),
                            Instant.now(),
                            null,
                            envelope.metrics());
            handler.validate(t); // 모순이면 예외 → 테스트 실패
        }
    }

    @Test
    void 반복하면_odom과_배터리_상태가_진전된다() {
        SimState state = new SimState("amr-0001", 37.5, 127.0);
        for (int i = 0; i < 50; i++) {
            profile.step(state);
        }
        assertThat(state.tick).isEqualTo(50);
        // 순찰로 odom이 원점에서 이동했다.
        assertThat(Math.hypot(state.odomX, state.odomY)).isGreaterThan(0.0);
    }

    @Test
    void seed는_같은_사이트_anchor를_공유하되_odom_시작위상을_분산한다() {
        int count = 10;
        SimState first = seeded(0, count);
        SimState second = seeded(1, count);
        // 단일 사이트: anchor(lat/lng)는 모두 동일.
        assertThat(second.lat).isEqualTo(first.lat);
        assertThat(second.lng).isEqualTo(first.lng);
        // 위상 분산: 시작 odom 지점이 서로 다르다(lockstep 겹침 방지).
        assertThat(Math.hypot(second.odomX - first.odomX, second.odomY - first.odomY))
                .isGreaterThan(0.0);
    }

    @Test
    void seed한_모든_대의_시작_odom이_서로_다르다() {
        int count = 10;
        long distinct =
                java.util.stream.IntStream.range(0, count)
                        .mapToObj(i -> seeded(i, count))
                        .map(s -> s.odomX + "," + s.odomY)
                        .distinct()
                        .count();
        assertThat(distinct).as("%d대가 서로 다른 시작 위상", count).isEqualTo(count);
    }

    @Test
    void seed한_봉투도_검증을_통과한다() {
        SimState state = seeded(3, 10);
        TelemetryRequest envelope = profile.step(state);
        assertThat(validator.validate(envelope)).isEmpty();
        Telemetry t =
                new Telemetry(
                        envelope.deviceId(),
                        envelope.deviceType(),
                        envelope.timestamp(),
                        Instant.now(),
                        null,
                        envelope.metrics());
        handler.validate(t);
    }

    private SimState seeded(int index, int count) {
        SimState s = new SimState("amr-" + index, 37.5, 127.0);
        profile.seed(s, index, count);
        return s;
    }

    @Test
    void 배터리가_낮으면_충전소로_복귀해_충전한다() {
        SimState state = new SimState("amr-0001", 37.5, 127.0);
        state.batteryPercent = 15; // 저전력
        boolean charged = false;
        for (int i = 0; i < 500 && !charged; i++) {
            profile.step(state);
            charged = state.charging;
        }
        assertThat(charged).as("저전력이면 충전소 복귀 후 충전 진입").isTrue();
    }
}
