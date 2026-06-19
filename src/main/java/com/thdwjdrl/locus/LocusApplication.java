package com.thdwjdrl.locus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Locus 애플리케이션 진입점.
 *
 * <p>컴포넌트 스캔 루트는 {@code com.thdwjdrl.locus} 이며 {@code core}와 {@code app}을 모두 덮는다. 단, {@code core}에는
 * Spring 컴포넌트가 존재하지 않는다(결정 0002: infra-free 코어). 실제 빈은 모두 {@code app} 슬라이스에만 있다.
 */
@SpringBootApplication
public class LocusApplication {

    public static void main(String[] args) {
        SpringApplication.run(LocusApplication.class, args);
    }
}
