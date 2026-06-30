# Locus — 실시간 디바이스 텔레메트리 수집·처리 파이프라인

물리 디바이스(폰·로봇·센서·장비)가 위치·상태를 실시간으로 올리고, 한 명이 다수를 모니터링하는 백본입니다.
디바이스를 추상화해서 폰은 구체 타입(`PHONE`)일 뿐이고, 코어는 디바이스 종류를 모릅니다. 어떤 물리 객체든 같은 파이프라인으로 수집·반영합니다.

> IoT·디지털 트윈 맥락의 텔레메트리 백본입니다. 구체 적용 예: 현장학습 인솔 교사가 다수 학생 단말의 위치·상태를 실시간으로 모니터링.

---

## 핵심 결과 — 적재 처리량 약 44배

이 프로젝트는 추측이 아니라 측정으로 판단합니다. 재현 → 병목 확인 → 재설계 → 재측정 순서로 남깁니다.

단건 insert로 시작한 적재 경로가 HDD fsync에 묶여 약 33 req/s에서 막히는 것을 측정으로 확인했고, 배치 적재로 1,437 req/s까지 올렸습니다.

| | 단건(M0) | 배치(M1) |
|---|---|---|
| 적재 포화점 | 33 req/s | **1,437 req/s (약 44×)** |
| 요청당 fsync | 약 1.2 | 0.059 |
| 내구성 | 안전 | **안전 유지(flush=1)** |

- **측정값은 계산과 일치합니다.** HDD fsync 한 번이 약 25ms이므로 디스크가 낼 수 있는 fsync는 초당 약 40회입니다. 요청당 fsync가 약 1.2회였으니 `40 ÷ 1.2 ≈ 33`으로, 측정한 포화점과 같습니다.
- **디스크가 병목임을 세 지표로 확인했습니다.** CPU 유휴(2~8%), HikariCP 커넥션 대기 적체, 디스크 `%util` 97%. k6·`iostat`·Prometheus/Grafana로 측정했고 원본 로그는 [docs/measurements/](docs/measurements/)에 있습니다.
- **처리량을 높인 원인은 배치입니다.** 변경을 변수별로 나눠 측정한 결과(A0~A3), 배치로 요청당 fsync를 1.2에서 0.06으로 줄이자 같은 디스크가 약 44배를 처리했습니다. 내구성을 포기하는 설정(flush=2)은 포화점을 올리지 못해, 내구성을 지키는 배치(A2)를 채택했습니다.

상세: [M0 측정](docs/measurements/M0.md) · [M1 측정(A0~A3 변수 분해)](docs/measurements/M1.md)
측정 캡처: ![capacity](docs/measurements/img/m0-capacity-clean.png)

> 관제 지도 데모(GIF): _(추가 예정)_

---

## 무엇을, 왜 이렇게

**무엇** — 디바이스가 1초 주기로 텔레메트리(위치·배터리·네트워크·상태)를 올리면, 서버가 수집·검증·저장하고 관제 화면이 디바이스별 최신 상태와 이력을 조회합니다.

**왜 이 도메인** — 물리 세계의 상태를 디지털에 실시간으로 반영하는 시스템(IoT·디지털 트윈)에서 핵심은 고빈도 텔레메트리를 유실 없이 받아 처리하는 파이프라인입니다. 이 프로젝트는 그 백본을 직접 만들고, 가장 단순한 구현에서 시작해 부하로 한계를 재현하고 측정 근거로 개선하는 과정을 기록합니다. 디바이스 타입에 무관한 코어로 설계해서 폰·로봇·센서 등 어떤 물리 객체로도 확장됩니다.

---

## 아키텍처 결정

결정의 이유와 기각한 대안을 [ADR](docs/decisions/)에 남깁니다. 코드보다 "왜"를 먼저 보면 좋습니다.

- **측정 주도** — 단순한 정답부터 만들고(YAGNI), 부하·프로파일링으로 병목을 찾고, 측정 근거로만 개선합니다. 효과가 없던 시도도 기록합니다.
- **저장소: MySQL → TimescaleDB(로드맵)** — M1에서 병목이 InnoDB B-tree의 제자리 갱신(랜덤 fsync)임을 측정했습니다. 시계열 적재에는 순차 쓰기(하이퍼테이블)가 맞다고 판단했습니다. [ADR 0008](docs/decisions/0008-telemetry-store-timescaledb.md)
- **메시징: Redis Streams (Kafka 아님)** — 팬아웃(fan-out)·결합도 분리는 Consumer Group으로 충분하고 이 규모에 가볍습니다. 최대 처리량은 메시지 큐가 아니라 저장소가 정한다는 것을 측정으로 확인했습니다. 이 구성이 한계에 도달하면 측정 결과를 보고 Kafka 전환을 검토합니다. [ADR 0007](docs/decisions/0007-messaging-storage-redis-streams-and-governance.md)
- **DeviceType이 데이터 거버넌스 축** — 디바이스 타입이 보존 정책과 데이터 도달 범위를 가릅니다(예: 위치 데이터 최소 보존). [ADR 0007](docs/decisions/0007-messaging-storage-redis-streams-and-governance.md)
- **포트는 교체 지점에만** — 헥사고날을 전면 채택하지 않고, 구현을 교체할 계획이 있는 지점에만 출력 포트를 둡니다. [ADR 0004](docs/decisions/0004-ports-only-at-improvement-seams.md)
- **core는 infra-free + ArchUnit** — 도메인·전략·엔진은 Spring·Kafka·Redis·Web에 의존하지 못합니다(빌드 게이트로 강제). [ADR 0002](docs/decisions/0002-single-module-with-archunit.md) · [ADR 0003](docs/decisions/0003-feature-slice-with-core-app-split.md)

---

## 구현 하이라이트

- **수집 적재 전략을 포트로 교체** — `TelemetryIngestPort`와 `@ConditionalOnProperty(locus.ingest.mode)`로 `direct`(단건)와 `queue`(인메모리 큐 + 배치 워커)를 설정만으로 전환합니다. before/after를 A/B로 측정했습니다.
- **배치 워커** — `BlockingQueue`와 전용 워커가 N건을 한 트랜잭션·멀티로우 INSERT로 적재합니다(`JdbcTemplate.batchUpdate`, `rewriteBatchedStatements`). `SmartLifecycle`로 종료 시 잔여분을 flush하고, 큐가 가득 차면 drop하면서 메트릭을 남깁니다(텔레메트리는 유실 허용).
- **API 의미론** — `202`(수집 수락), `404`(없음), `409`(`UNIQUE(device_id, recorded_at)` 멱등 충돌).
- **부하·관측 인프라** — 가상 스레드 시뮬레이터(1디바이스=1스레드), Actuator→Prometheus(p95·p99·GC·HikariCP), k6(열린·닫힌 부하 모델), 2-머신 측정 [RUNBOOK](docs/measurements/RUNBOOK.md).

---

## 기술 스택
- **현재**: Java 21 · Spring Boot 3.4 · Gradle(Kotlin DSL) · MySQL 8 · k6 · Prometheus/Grafana · Docker · ArchUnit
- **로드맵**: TimescaleDB(시계열 저장) · Redis(캐시) · WebSocket(실시간 푸시) · MQTT(수집 전송) · Kubernetes

---

## 빠른 시작
```bash
# 0) (선택) 로컬 환경변수 — 기본값으로도 동작
cp .env.example .env

# 1) 인프라 기동 (현재: MySQL)
docker compose up -d

# 2) 앱 실행 (호스트 JVM에서 직접 — M8 전까지)
./gradlew bootRun

# 3) 시뮬레이터로 가상 디바이스 N대 전송
./gradlew bootRun --args='--spring.profiles.active=simulator'

# 4) 단위 + 웹 테스트 (빠름, Docker 불필요)
./gradlew test

# 5) 통합 테스트 (Testcontainers 실 MySQL, Docker 필요)
./gradlew check
```
- 앱: http://localhost:8093 · 메트릭: http://localhost:8093/actuator/prometheus
- 관제 지도: http://localhost:8093 (Leaflet, 디바이스별 최신 위치 폴링)

> **통합 테스트 로컬 실행(colima 사용 시):** Testcontainers가 colima 소켓·새 Docker API를 인식하도록 아래 env가 필요합니다(빌드가 테스트 JVM에 전달). CI(GitHub Actions 표준 Docker)는 불필요합니다.
> ```bash
> export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
> export DOCKER_API_VERSION=1.44
> export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
> ```

---

## 문서
- [docs/STATUS.md](docs/STATUS.md) — 진행 현황(기준 문서)
- [docs/STRUCTURE.md](docs/STRUCTURE.md) — 파일트리 ↔ 결정 매핑
- [docs/ROADMAP.md](docs/ROADMAP.md) — 마일스톤 ↔ 트리 위치 + 목표 SLO
- [docs/decisions/](docs/decisions/) — ADR(왜 + 기각한 대안)
- [docs/measurements/](docs/measurements/) — 마일스톤별 측정 기록(원본 로그 포함)
- [SECURITY.md](SECURITY.md) — 보안 정책(민감정보·비밀 관리)

---

## 로드맵
- ✅ **M0** 도메인·수집·조회·시뮬레이터·관제 지도 + 측정 baseline(포화점 33, 디스크 fsync 병목 확인)
- ✅ **M1** 적재 포화점 높이기 — 인메모리 큐 + 배치로 33→1,437 req/s(내구성 유지)
- ⬜ **M2** TimescaleDB 전환(순차 저장) — 적재 포화점 재측정
- ⬜ **M4** 실시간 — Redis 최신상태 캐시 + WebSocket 푸시(폴링 대체)
- ⬜ **MQTT** 수집 경로(IoT 표준 전송)
- ⬜ **M5~** 지오펜스 판정 · 민감정보 보호·보존 · 컨테이너·k8s · (페이즈2) 명령 다운링크·정합성

> 목표 SLO와 전체 마일스톤은 [ROADMAP](docs/ROADMAP.md)에 있습니다. 측정 근거 없이 기능을 늘리지 않고, 각 단계를 before/after로 정당화합니다.
