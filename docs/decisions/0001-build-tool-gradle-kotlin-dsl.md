# 0001 — 빌드 도구: Gradle (Kotlin DSL)

- 상태: 확정
- 일자: 2026-06-19

## 결정
Gradle + **Kotlin DSL**(`build.gradle.kts`)을 쓴다. 버전은 wrapper로 고정(Gradle 8.11.1).
Java는 toolchain으로 **21 고정** — 빌드를 어떤 JVM에서 돌리든 컴파일 타깃은 21.

## 맥락
- 스택은 Java 21 + Spring Boot 3.x. Gradle은 이 조합의 사실상 표준이고 멀티모듈 전환·빌드 캐싱·Docker 빌드(M8)와 궁합이 좋다.
- 환경에 Gradle이 없어 `brew install gradle`(9.6)로 설치 → 그 Gradle로 8.11.1 wrapper를 생성. 이후엔 `./gradlew`만 쓴다.

## 기각된 대안
- **Groovy DSL**: 레퍼런스는 많지만 타입 안정성·IDE 자동완성이 약하다.
- **Maven**: 안정적이나 XML이 장황하고 빌드 로직 표현력이 떨어진다.

## 영향
- `build.gradle.kts`, `settings.gradle.kts`, `gradle/wrapper/*`, `gradlew`.
- M8에서 멀티스테이지 Docker 빌드 시 `bootJar` 산출물을 사용.
