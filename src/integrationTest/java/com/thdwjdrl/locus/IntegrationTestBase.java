package com.thdwjdrl.locus;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합 테스트 베이스 — 실제 TimescaleDB 컨테이너 1개를 <b>싱글톤</b>으로 공유.
 *
 * <p>{@code @Testcontainers}/{@code @Container}(클래스마다 start/stop) 대신 정적 초기화로 <b>JVM당 1번만</b> 시작하고
 * 멈추지 않는다(Ryuk이 JVM 종료 시 정리). 여러 테스트 클래스가 같은 살아있는 컨테이너를 쓰게 하기 위함.
 *
 * <p>{@code @ServiceConnection}이 이 컨테이너에 맞춰 datasource를 자동 구성한다. JSONB 컬럼·임베디드·복합 PK 제약 등
 * TimescaleDB/PostgreSQL 전용 동작을 진짜로 검증(mock·H2로는 불가).
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("timescale/timescaledb:2.17.2-pg16")
                            .asCompatibleSubstituteFor("postgres"));

    static {
        POSTGRES.start();
    }
}
