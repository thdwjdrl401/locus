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

    /**
     * {@code max_connections}를 올려 띄운다 — <b>앱 설정은 그대로 두기 위해서</b>.
     *
     * <p>Spring TestContext는 컨텍스트를 캐시해 여러 개를 동시에 살려두고, 컨텍스트마다 HikariCP가 앱 기본값({@code
     * DB_POOL_MAX:16})만큼 커넥션을 붙든다. 테스트 클래스가 늘면 Postgres 기본 {@code max_connections}(100)를 넘겨 뒤에 뜨는
     * 컨텍스트가 {@code FATAL: sorry, too many clients already}로 통째로 실패한다(실행 순서를 따라 실패가 옮겨다녀 원인 추적이
     * 어렵다).
     *
     * <p>앱 쪽 풀 크기를 테스트에서만 줄이면 테스트가 재는 조건이 실제 실행 조건과 달라진다. 그래서 손대는 쪽은 앱이 아니라 테스트용 DB 쪽이다. {@code
     * fsync=off}는 Testcontainers 기본값이라 명시적으로 유지한다(빼면 테스트가 느려진다).
     */
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                            DockerImageName.parse("timescale/timescaledb:2.17.2-pg16")
                                    .asCompatibleSubstituteFor("postgres"))
                    .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=300");

    static {
        POSTGRES.start();
    }
}
