# Locus — 실시간 디바이스 위치·상태 추적 플랫폼

디바이스가 위치·상태를 올리고, 한 명이 다수를 실시간 모니터링하는 플랫폼.
디바이스를 추상화해 폰은 구체 타입(`PHONE`)일 뿐이며, 코어는 로봇·드론·태그에도 적용된다.

> 데모 시나리오: 외부활동(현장학습) 중 인솔 교사가 다수 학생 단말의 위치·상태를 모니터링.

## 개발 철학
**순진하게 구현 → 일부러 부하 → 문제 재현 → 진단 → 개선 → 측정으로 증명.**
모든 변경은 `docs/measurements/`에 before/after 수치로 남긴다. 숫자 없으면 개선이 아니다.

## 스택
Java 21 · Spring Boot 3.4 · Gradle (Kotlin DSL) · MySQL 8 · (M2~)Kafka · (M4~)Redis · k6 · Docker/k8s(M8)

## 빠른 시작
```bash
# 0) (선택) 로컬 환경변수 — 기본값으로도 동작, 바꾸려면:
cp .env.example .env

# 1) 인프라 기동 (M0: MySQL만)
docker compose up -d

# 2) 앱 실행 (호스트 JVM에서 직접 — M8 전까지)
./gradlew bootRun

# 3) 시뮬레이터로 실행 (가상 폰 N대 전송)
./gradlew bootRun --args='--spring.profiles.active=simulator'

# 4) 아키텍처 경계 검증 + 테스트
./gradlew test
```
- Actuator/Prometheus: http://localhost:8080/actuator/prometheus

## 구조와 결정
이 프로젝트는 **결정의 이유**를 문서로 남긴다. 코드를 읽기 전에 먼저 보면 좋다.

- [docs/STRUCTURE.md](docs/STRUCTURE.md) — 파일트리 ↔ 결정 매핑
- [docs/ROADMAP.md](docs/ROADMAP.md) — 마일스톤 ↔ 트리 위치
- [docs/decisions/](docs/decisions/) — ADR (왜 이렇게 했나 + 기각된 대안)
- [docs/measurements/](docs/measurements/) — 마일스톤별 측정 기록

### 한 장 요약
- **단일 모듈 + ArchUnit** — `core`는 인프라에 의존 못 한다(빌드 게이트). [ADR 0002](docs/decisions/0002-single-module-with-archunit.md)
- **core(도메인·전략·엔진) / app(배선, 기능별 슬라이스)** — [ADR 0003](docs/decisions/0003-feature-slice-with-core-app-split.md)
- **포트는 개선 이음새(M2·M4·M5)에만** — 헥사고날 부분 차용. [ADR 0004](docs/decisions/0004-ports-only-at-improvement-seams.md)
- **양축 추상화** — DeviceType/MissionType은 갈아끼우고, 가운데 판정 엔진만 단단히.
