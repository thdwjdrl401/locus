plugins {
    java
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.6"
    id("com.diffplug.spotless") version "6.25.0"
}

group = "com.thdwjdrl"
version = "0.0.1-SNAPSHOT"
description = "Locus - 실시간 디바이스 위치·상태 추적 플랫폼"

java {
    // 결정 0001: Java 21 고정. 실행 JVM이 무엇이든 toolchain이 21을 강제한다.
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val archunitVersion = "1.3.0"

dependencies {
    // --- M0: 수집·조회·검증·측정의 최소 묶음 ---
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation") // 텔레메트리 봉투 검증
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")   // 단건 insert(순진하게 먼저)
    implementation("org.springframework.boot:spring-boot-starter-actuator")   // 측정 인프라

    // 측정: Actuator -> Prometheus 노출 (M0부터 baseline 수집)
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // DB: M2 TimescaleDB(PostgreSQL). Flyway로 스키마 관리 (ddl-auto=validate).
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    // M4: Redis — 최신상태 캐시(읽기경로). M4b에서 같은 Redis를 Streams fan-out으로 확장(ADR 0007).
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // --- 테스트 ---
    testImplementation("org.springframework.boot:spring-boot-starter-test")

    // 결정 0002: ArchUnit으로 core -> app/infra 의존을 빌드 단에서 금지
    testImplementation("com.tngtech.archunit:archunit-junit5:$archunitVersion")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// 테스트 2층 분리(jvm-test-suite):
//   test            = 단위 + 웹(MockMvc), 빠름, Docker 불필요
//   integrationTest = 통합(Testcontainers 실 TimescaleDB), 느림, Docker 필요  (src/integrationTest/java)
testing {
    suites {
        val integrationTest by registering(JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation("org.springframework.boot:spring-boot-starter-test")
                implementation("org.springframework.boot:spring-boot-testcontainers")
                implementation("org.testcontainers:junit-jupiter")
                implementation("org.testcontainers:postgresql")
            }
            targets {
                all {
                    testTask.configure {
                        shouldRunAfter(tasks.named("test"))
                        // 비표준 Docker 소켓(colima 등): Gradle 프로세스의 환경변수를 테스트 JVM에 명시 전달.
                        // CI의 표준 Docker(/var/run/docker.sock)에선 이 변수들이 없어 영향 없음.
                        listOf(
                            "DOCKER_HOST",
                            "DOCKER_API_VERSION",
                            "TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE",
                            "TESTCONTAINERS_HOST_OVERRIDE",
                        ).forEach { key -> System.getenv(key)?.let { environment(key, it) } }
                        // 매우 새 Docker 데몬(colima의 Docker 29, 최소 API 1.44) 대응:
                        // docker-java가 기본 1.32로 협상해 400나는 걸 막는다. CI(표준 Docker)엔 env가 없어 미적용.
                        System.getenv("DOCKER_API_VERSION")?.let { systemProperty("api.version", it) }
                    }
                }
            }
        }
    }
}

// integrationTest가 main의 implementation/runtimeOnly(web·jpa·validation·postgresql·flyway 등)를 그대로 상속
configurations.named("integrationTestImplementation") {
    extendsFrom(configurations.implementation.get())
}
configurations.named("integrationTestRuntimeOnly") {
    extendsFrom(configurations.runtimeOnly.get())
}

// check는 단위/웹 + 통합 모두 실행 (CI 게이트)
tasks.named("check") {
    dependsOn(testing.suites.named("integrationTest"))
}

// STATUS 하네스(CLAUDE.md §7): git hooksPath를 레포 추적 .githooks로 연결한다.
// .githooks/pre-commit이 "실질 변경 스테이징 시 STATUS.md 동반 갱신"을 강제.
// 새 클론에서도 첫 ./gradlew 빌드에 자동 설정된다(.git 없으면 건너뜀 — CI tarball 등).
tasks.register<Exec>("installGitHooks") {
    description = "git core.hooksPath를 .githooks로 설정 (STATUS 하네스)"
    onlyIf { file(".git").exists() }
    commandLine("git", "config", "core.hooksPath", ".githooks")
}
tasks.named("compileJava") { dependsOn("installGitHooks") }

// 포매터: 코드 스타일 잡음 제거 + CI에서 spotlessCheck로 강제.
// google-java-format AOSP 변형 = 4-space 들여쓰기.
spotless {
    java {
        googleJavaFormat("1.22.0").aosp()
        target("src/**/*.java")
        // package-info의 라이선스 헤더 강요 안 함. 기본 정리만.
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
