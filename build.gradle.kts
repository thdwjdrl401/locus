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

    // DB: M0는 MySQL 단일. (M2 Kafka, M4 Redis 의존성은 해당 마일스톤에서 추가)
    runtimeOnly("com.mysql:mysql-connector-j")

    // --- 테스트 ---
    testImplementation("org.springframework.boot:spring-boot-starter-test")

    // 결정 0002: ArchUnit으로 core -> app/infra 의존을 빌드 단에서 금지
    testImplementation("com.tngtech.archunit:archunit-junit5:$archunitVersion")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

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
