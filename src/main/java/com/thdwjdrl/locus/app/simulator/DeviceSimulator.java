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
        executor = Executors.newVirtualThreadPerTaskExecutor();

        launch(DeviceType.PHONE, "phone-%04d", props.getPhoneCount(), client);
        launch(DeviceType.AMR, "amr-%04d", props.getAmrCount(), client);

        log.info(
                "시뮬레이터 시작: 폰 {}대 · AMR {}대 × {}ms 주기 → {}",
                props.getPhoneCount(),
                props.getAmrCount(),
                props.getIntervalMs(),
                props.getTargetBaseUrl());
    }

    private void launch(DeviceType type, String idFormat, int count, RestClient client) {
        MovementProfile profile = profiles.get(type);
        if (profile == null || count <= 0) {
            return;
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            String deviceId = String.format(idFormat, i);
            // 한 지점(현장/사이트) 주변에 분산 시작. AMR은 이 좌표가 사이트 anchor.
            SimState state =
                    new SimState(
                            deviceId,
                            37.5 + rnd.nextDouble() * 0.01,
                            127.0 + rnd.nextDouble() * 0.01);
            executor.submit(new SimulatedDevice(state, profile, client, props.getIntervalMs()));
        }
    }

    @PreDestroy
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
            log.info("시뮬레이터 종료");
        }
    }
}
