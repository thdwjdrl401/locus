# Locus — 실시간 디바이스 위치·상태 추적 플랫폼

디바이스가 위치·상태를 올리고, 한 명이 다수를 실시간 모니터링하는 플랫폼.
디바이스를 추상화해 폰은 구체 타입(`PHONE`)일 뿐이며, 코어는 로봇·드론·태그에도 적용된다.

> 데모 시나리오: 외부활동(현장학습) 중 인솔 교사가 다수 학생 단말의 위치·상태를 모니터링.

## 엔지니어링 원칙
**가장 단순한 정답부터 만들고(YAGNI) → 부하·프로파일링으로 실제 병목을 찾고 → 측정 근거로 개선한다.**
성능·구조 변경은 `docs/measurements/`에 before/after 수치로 남긴다. 추측이 아니라 측정으로 판단한다.

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

# 4) 단위 + 웹 테스트 (빠름, Docker 불필요)
./gradlew test

# 5) 통합 테스트 (Testcontainers 실 MySQL, Docker 필요) — test + integrationTest를 함께
./gradlew check
```
- Actuator/Prometheus: http://localhost:8080/actuator/prometheus

> **통합 테스트 로컬 실행 (colima 사용 시):** Testcontainers가 colima 소켓·새 Docker(29) API를 인식하도록 아래 env가 필요하다(빌드가 테스트 JVM에 전달). CI(GitHub Actions 표준 Docker)는 불필요.
> ```bash
> export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
> export DOCKER_API_VERSION=1.44
> export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
> ```
> colima는 MySQL 컨테이너에 여유가 필요하다: `colima start --cpu 4 --memory 6`. (Docker Desktop을 쓰면 위 env 없이 동작.)

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
