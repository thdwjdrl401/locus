package com.thdwjdrl.locus.app.simulator;

import com.thdwjdrl.locus.core.domain.DeviceType;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 시뮬레이터 오케스트레이터 ({@code simulator} 프로파일에서만 활성).
 *
 * <p>앱이 준비되면 N대의 {@link SimulatedDevice}를 각각 가상 스레드로 띄운다. 실행:
 *
 * <pre>./gradlew bootRun --args='--spring.profiles.active=simulator'</pre>
 */
@Component
@Profile("simulator")
public class DeviceSimulator {

    private static final Logger log = LoggerFactory.getLogger(DeviceSimulator.class);

    private final SimulatorProperties props;
    private final Map<DeviceType, MovementProfile> profiles;
    private ExecutorService executor;

    public DeviceSimulator(SimulatorProperties props, List<MovementProfile> profiles) {
        this.props = props;
        this.profiles =
                profiles.stream()
                        .collect(
                                Collectors.toMap(MovementProfile::deviceType, Function.identity()));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        RestClient client = RestClient.create(props.getTargetBaseUrl());
        MovementProfile phone = profiles.get(DeviceType.PHONE);
        executor = Executors.newVirtualThreadPerTaskExecutor();

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < props.getDeviceCount(); i++) {
            String deviceId = String.format("phone-%04d", i);
            // 한 지점(예: 현장학습 장소) 주변에 분산 시작
            SimState state =
                    new SimState(
                            deviceId,
                            37.5 + rnd.nextDouble() * 0.01,
                            127.0 + rnd.nextDouble() * 0.01);
            executor.submit(new SimulatedDevice(state, phone, client, props.getIntervalMs()));
        }
        log.info(
                "시뮬레이터 시작: {}대 × {}ms 주기 → {}",
                props.getDeviceCount(),
                props.getIntervalMs(),
                props.getTargetBaseUrl());
    }

    @PreDestroy
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
            log.info("시뮬레이터 종료");
        }
    }
}
