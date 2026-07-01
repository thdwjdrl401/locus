# 프로젝트 구조 ↔ 결정 매핑

파일트리의 각 부분이 **어느 결정에서 나왔고, 무엇을 담는지**를 한눈에 본다.
결정의 근거는 [decisions/](decisions/), 마일스톤별 위치는 [ROADMAP.md](ROADMAP.md).

## 전체 트리

```
locus/
├── build.gradle.kts          ← 결정 0001: Gradle Kotlin DSL, Java 21 toolchain, M0 의존성
├── settings.gradle.kts       ← 결정 0002: 단일 모듈 선언
├── gradle.properties         ← 빌드 캐시/병렬/구성 캐시
├── gradlew / gradle/wrapper   ← Gradle 8.11.1 wrapper 고정
├── docker-compose.yml        ← 인프라만(M0=mysql). 앱은 M8 전까지 호스트 직접 실행
│
├── src/main/java/com/thdwjdrl/locus/      ← 결정 0006: 베이스 패키지
│   ├── LocusApplication.java              진입점
│   │
│   ├── core/                  ← 결정 0002·0003: infra-free 코어. ArchUnit 감시
│   │   ├── domain/            도메인 모델 (Telemetry, Device, Location VO, Mission*, enums)
│   │   ├── strategy/          양축 추상화 인터페이스 (DeviceTypeHandler/MovementProfile/MissionType/ReachEvaluator)
│   │   └── engine/            판정 엔진 (RadiusEvaluator, 상태전이)
│   │
│   └── app/                   ← 결정 0003: 배선. 기능별 슬라이스
│       ├── telemetry/         수집·조회 (M0~). M2 수집 포트, M7 커서 페이징
│       ├── device/            디바이스 조회 + PhoneHandler/PhoneProfile (M0~). M3 새 타입
│       ├── simulator/         ← 결정 0005: 폰 시뮬레이터 (simulator 프로파일)
│       ├── config/            인프라 설정 (M4 Redis 캐시+Streams ... 점증; M2는 인메모리 큐로 인프라 0)
│       └── support/           횡단 관심사 (예외·응답·검증·로그마스킹)
│
├── src/main/resources/
│   ├── application.yml            M0: MySQL + Actuator/Prometheus
│   └── application-simulator.yml  ← 결정 0005: 시뮬레이터 프로파일 설정
│
├── src/test/java/com/thdwjdrl/locus/
│   └── architecture/
│       └── CoreIsolationTest.java ← 결정 0002 집행자: core→infra 의존 금지
│
└── docs/
    ├── decisions/             ADR (왜 이렇게 했나 + 기각된 대안)
    ├── specs/                 마일스톤 착수 전 스펙 노트 (M4 실시간 읽기경로 ...)
    ├── measurements/          ← 계획서 §9: Mx별 before/after 수치
    ├── STRUCTURE.md           (이 문서)
    └── ROADMAP.md             마일스톤 → 트리 위치 매핑
```
`Mission*` = 페이즈 1에서는 필드만 정의, 동작은 M9.

## 핵심 규칙 요약
| 규칙 | 출처 | 집행 방법 |
|---|---|---|
| `core`는 Spring/Kafka/Redis/Web에 의존 금지 | 0002·0003 | `CoreIsolationTest` (빌드 게이트) |
| `core`에 jakarta.* (JPA/Validation)는 허용 | 0003 메모 | 테스트 금지목록에서 제외 |
| 양축 추상화는 인터페이스(core) ↔ 구현(app) | 0003 | M3·M9에서 core diff 0줄로 증명 |
| 포트는 개선 이음새(M2/M4/M5)에만 | 0004 | 그 외엔 직접 호출 |
| 인프라는 마일스톤마다 하나씩 | 계획서 §11 | docker-compose 주석으로 점증 |
| 모든 개선은 측정으로 증명 | 계획서 §4·§9 | docs/measurements/Mx.md |
