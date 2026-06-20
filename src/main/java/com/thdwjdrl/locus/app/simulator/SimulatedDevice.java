package com.thdwjdrl.locus.app.simulator;

import com.thdwjdrl.locus.app.telemetry.TelemetryRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * 가상 디바이스 1대 = 가상 스레드 1개(Java 21). 주기적으로 봉투를 만들어 수집 API로 POST한다.
 *
 * <p>오프라인(신호 끊김) 상태면 전송을 건너뛴다(버퍼링/유실 모델링). 일시 오류는 무시하고 계속 돈다(부하 생성이 목적).
 */
public class SimulatedDevice implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(SimulatedDevice.class);

    private final SimState state;
    private final MovementProfile profile;
    private final RestClient client;
    private final long intervalMs;

    public SimulatedDevice(
            SimState state, MovementProfile profile, RestClient client, long intervalMs) {
        this.state = state;
        this.profile = profile;
        this.client = client;
        this.intervalMs = intervalMs;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                TelemetryRequest envelope = profile.step(state);
                if (state.online) {
                    send(envelope);
                }
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.debug("{} 전송 실패(무시): {}", state.deviceId, e.getMessage());
            }
        }
    }

    private void send(TelemetryRequest envelope) {
        client.post()
                .uri("/api/telemetry")
                .contentType(MediaType.APPLICATION_JSON)
                .body(envelope)
                .retrieve()
                .toBodilessEntity();
    }
}
