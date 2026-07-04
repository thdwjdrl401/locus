package com.thdwjdrl.locus.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.json.JacksonTester;

/**
 * 수집 봉투 timestamp 형식 가드 (M-MQTT).
 *
 * <p>숫자 timestamp = epoch 밀리초(디바이스 관례, {@code read-date-timestamps-as-nanoseconds=false}), ISO-8601
 * 문자열은 그대로. 이 계약이 깨지면 MQTT 벤치·디바이스 수집이 전량 검증 드롭되므로 두 형식을 고정한다.
 */
@JsonTest
class TelemetryRequestJsonTest {

    @Autowired private JacksonTester<TelemetryRequest> json;

    @Test
    void 숫자_timestamp는_epoch_밀리초로_해석된다() throws Exception {
        long epochMs = 1_783_060_011_248L;
        TelemetryRequest parsed =
                json.parseObject(
                        """
                        {"deviceId":"d-1","deviceType":"PHONE","timestamp":%d,
                         "metrics":{"permission":"WHILE_IN_USE","sharingEnabled":true}}
                        """
                                .formatted(epochMs));
        assertThat(parsed.timestamp()).isEqualTo(Instant.ofEpochMilli(epochMs));
    }

    @Test
    void ISO_문자열_timestamp는_그대로_해석된다() throws Exception {
        TelemetryRequest parsed =
                json.parseObject(
                        """
                        {"deviceId":"d-1","deviceType":"PHONE","timestamp":"2026-07-02T08:16:44.396Z",
                         "metrics":{"permission":"WHILE_IN_USE","sharingEnabled":true}}
                        """);
        assertThat(parsed.timestamp()).isEqualTo(Instant.parse("2026-07-02T08:16:44.396Z"));
    }
}
