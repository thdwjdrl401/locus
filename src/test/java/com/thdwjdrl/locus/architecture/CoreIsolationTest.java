package com.thdwjdrl.locus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 결정 0002의 집행자: {@code core}가 infra-free임을 빌드 단에서 강제한다.
 *
 * <p>멀티모듈의 컴파일 강제를 단일 모듈에서 ArchUnit 테스트로 대체한다. 이 테스트가 깨지면 추상화 경계가 샌 것이다.
 *
 * <p>참고: JPA/Validation 표준 애너테이션(jakarta.*)은 의도적으로 허용한다 (결정 0003 메모 — 도메인↔엔티티 매퍼 보일러플레이트를 피하기 위한
 * 실용적 타협). 금지 대상은 프레임워크·메시징·캐시 같은 진짜 인프라뿐이다.
 */
@AnalyzeClasses(packages = "com.thdwjdrl.locus")
class CoreIsolationTest {

    // allowEmptyShould(true): 골격 단계엔 core에 실제 클래스가 없다(package-info뿐).
    // M0에서 도메인 클래스가 생기면 규칙이 실효성을 갖는다. 그 전까지 빈 상태로 실패시키지 않는다.

    @ArchTest
    static final ArchRule core_는_app에_의존하지_않는다 =
            noClasses()
                    .that()
                    .resideInAPackage("..core..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..app..")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule core_는_인프라에_의존하지_않는다 =
            noClasses()
                    .that()
                    .resideInAPackage("..core..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework..", // Spring / Spring Boot
                            "org.apache.kafka..", // Kafka (M2)
                            "io.lettuce..", // Redis client (M4)
                            "redis.clients..", // Redis client (M4)
                            "jakarta.servlet.." // Web / Servlet
                            )
                    .allowEmptyShould(true);
}
