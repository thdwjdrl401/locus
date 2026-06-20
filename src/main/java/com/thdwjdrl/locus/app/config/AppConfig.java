package com.thdwjdrl.locus.app.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 공통 빈. {@link Clock}을 주입 가능하게 해 시각 의존 로직(receivedAt 등)을 테스트에서 고정할 수 있다. */
@Configuration
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
