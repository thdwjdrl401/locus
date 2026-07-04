# 프로젝트 구조 ↔ 결정 매핑

파일트리의 각 부분이 **어느 결정에서 나왔고, 무엇을 담는지**를 한눈에 본다.
결정의 근거는 [decisions/](decisions/), 마일스톤별 위치는 [ROADMAP.md](ROADMAP.md). (갱신 2026-07-03)

## 전체 트리

```
locus/
├── build.gradle.kts          ← 결정 0001: Gradle Kotlin DSL, Java 21 toolchain. 테스트 2층(test/integrationTest)
├── settings.gradle.kts       ← 결정 0002: 단일 모듈 선언
├── gradle.properties         ← 빌드 캐시/병렬/구성 캐시
├── gradlew / gradle/wrapper   ← Gradle 8.11.1 wrapper 고정
├── docker-compose.yml        ← 인프라만(마일스톤별 점증: M2=timescaledb, M4=redis, M-MQTT=mosquitto
│                                + node-exporter 관측 사이드카). 앱은 M8 전까지 호스트 직접 실행
├── docker-compose.monitoring.yml  관측 스택(Prometheus+Grafana) — SUT 밖(맥)에서 실행
├── docker/mosquitto/         ← M-MQTT: 브로커 설정(익명 LAN·영속화 끔)
├── .githooks/pre-commit      ← CLAUDE §7 집행: 실질 변경 시 STATUS.md 동반 갱신 강제
├── scripts/run-app.sh        ← 측정용 실행(고정 JVM 플래그·taskset 코어 핀·GC 로그)
├── load/                     ← k6 부하 스크립트(baseline/stress/capacity/latest-read) + 시드 SQL
├── monitoring/               ← prometheus.yml.example + Grafana provisioning(대시보드: m2·m4a·m4b)
│
├── src/main/java/com/thdwjdrl/locus/      ← 결정 0006: 베이스 패키지
│   ├── LocusApplication.java              진입점
│   │
│   ├── core/                  ← 결정 0002·0003: 프레임워크 런타임-free 코어. ArchUnit 감시
│   │   ├── domain/            도메인 모델 (Telemetry, Device, Location, enums)
│   │   ├── strategy/          양축 추상화 인터페이스 (DeviceTypeHandler{validate·gate}/MovementProfile). gate=타입별 최소수집(M3)
│   │   └── engine/            판정 엔진 자리 — M5에서 구현(현재 package-info만)
│   │
│   └── app/                   ← 결정 0003: 배선. 기능별 슬라이스
│       ├── telemetry/         수집·조회·fan-out (M0~).
│       │                        봉투(TelemetryRequest): 공통칸(id·type·time·location) + metrics 자유칸(JSONB, 타입 무관). Assembler는 passthrough(M3)
│       │                        수집: HTTP(direct/queue/stream 토글, 0004 이음새) + MQTT(MqttSubscriber 이음새, 0007)
│       │                        fan-out: Redis Streams storage/monitoring 컨슈머(M4b, 0007)
│       │                        조회: LATERAL 최신조회(M4a) + LatestStateLookup 포트(db/cache 토글, 0004)
│       ├── device/            디바이스 조회 + 타입 핸들러(PhoneHandler·AmrHandler)·metrics(PhoneMetrics·AmrMetrics). M3에서 AMR 추가(core diff=enum 1줄)
│       ├── simulator/         ← 결정 0005: 시뮬레이터. PhoneProfile·AmrProfile(M3, odom→lat/lng)로 타입별 생성
│       ├── config/            인프라 설정 (WebSocket/STOMP 등)
│       └── support/           횡단 관심사 (예외·응답·검증)
│
├── src/main/resources/
│   ├── application.yml            TimescaleDB·Redis·MQTT·수집 전략(locus.ingest/read/mqtt) 설정
│   ├── application-simulator.yml  ← 결정 0005: 시뮬레이터 프로파일 설정
│   ├── db/migration/              Flyway(V1 스키마+하이퍼테이블, V2 청크·retention, V3 device.org_id)
│   └── static/index.html          관제 지도(Leaflet + STOMP 구독, M4b 데모)
│
├── src/test/java/com/thdwjdrl/locus/          단위·웹(MockMvc) — 빠름, Docker 불필요
│   └── architecture/CoreIsolationTest.java     ← 결정 0002 집행자: core→infra 의존 금지
├── src/integrationTest/java/...                통합(Testcontainers 실 TimescaleDB·Redis·Mosquitto)
│                                                — 검증 게이트(CI `check`에 포함)
│
└── docs/
    ├── decisions/             ADR (왜 이렇게 했나 + 기각된 대안 + 재검토)
    ├── specs/                 마일스톤 착수 전 스펙 노트 (M4 실시간 읽기경로 ...)
    ├── measurements/          ← 계획서 §9: Mx별 before/after 수치 + Mx-raw/ 원본
    ├── PERFORMANCE.md         마일스톤 횡단 병목 이동 서사
    ├── STRUCTURE.md           (이 문서)
    └── ROADMAP.md             마일스톤 → 트리 위치 매핑 + 보류된 결정
```

## 핵심 규칙 요약
| 규칙 | 출처 | 집행 방법 |
|---|---|---|
| `core`는 Spring/Kafka/Redis/Web 런타임에 의존 금지 | 0002·0003 | `CoreIsolationTest` (빌드 게이트) |
| `core`에 영속 매핑 애너테이션(jakarta.* + Hibernate 매핑)은 허용 | 0003 메모 | 테스트 금지목록에서 제외 |
| 양축 추상화는 인터페이스(core) ↔ 구현(app) | 0003 | M3·M9에서 core diff 0줄로 증명 |
| 포트는 개선 이음새에만 — 교체 계획이 명시될 때 추가 | 0004 | M2 수집·M4 캐시·M5 지오펜스 + M-MQTT `MqttSubscriber`. 그 외엔 직접 호출 |
| 인프라는 마일스톤마다 하나씩 | 계획서 §11 | docker-compose 주석으로 점증 |
| 모든 개선은 측정으로 증명 | 계획서 §4·§9 | docs/measurements/Mx.md |
| 실질 변경은 STATUS 동반 갱신 | CLAUDE §7 | `.githooks/pre-commit` |
