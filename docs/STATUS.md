# 작업 현황 (STATUS) — 진행 현황의 기준 문서

> **이 문서 = 상시 갱신하는 진행 기록** (뭘 끝냈고 / 지금 뭐 하고 / 다음에 뭐 할지 / 왜 그렇게 정했는지의 기록).
> 역할 분담 — 이 문서는 **상태**만. *왜·어디에* = [ROADMAP](ROADMAP.md), *수치·해석* = [measurements/](measurements/), *마일스톤 횡단 병목 서사* = [PERFORMANCE](PERFORMANCE.md), *결정 이유(ADR)* = [decisions/](decisions/), *트리 매핑* = [STRUCTURE](STRUCTURE.md).
> 범례: ✅ 완료 · 🔄 진행 중 · ⬜ 예정 · ⏸ 보류(다른 마일스톤) · 🚧 막힘(외부의존). 작업·결정 끝낼 때마다 여기 갱신(CLAUDE.md §7).

---

## 현재 포커스 — M3 추상화 검증 완료 (디바이스 타입)

| | |
|---|---|
| **한 줄** | **M3 완료(2026-07-04)**: 수집 봉투를 디바이스 무관하게 재설계(공통칸 + `metrics` JSONB 자유칸)하고 최소수집 게이트를 `DeviceTypeHandler.gate()` 전략으로 옮긴 뒤, 둘째 타입 `AMR`을 추가해 **새 타입 추가 시 core diff = enum 값 1줄 + 게이트 훅 1개뿐**(engine·엔티티 불변)임을 실증. AMR 동작(검증·시뮬·metrics)은 전부 app. 폰 프라이버시 게이트는 동작 불변(테스트로 못박음). 단위·ArchUnit green(60 tests). 통합테스트는 로컬 Docker 미기동으로 컴파일까지 확인. |
| **직전 완료 (M-MQTT)** | IoT 표준 수집 전송(디바이스 uplink) + 병목 규명·개선 완결. 인입 병렬화로 3.25K→~9K(2.8배)·HTTP 천장 근접. 브로커=Mosquitto. 상세 [measurements/M-MQTT.md](measurements/M-MQTT.md). |
| **직전 완료 (M4b B)** | Redis Streams 적재 fan-out 박스 측정: **지속 10k 무손실**(워커4·MAXLEN400K)·**재시작 at-least-once**. 상세 [measurements/M4b.md](measurements/M4b.md)·[raw](measurements/M4b-raw/). 커밋 `f481eb2`·CI green. |
| **다음 한 걸음** | **측정 완료 — 병목 규명(2026-07-03)** → [measurements/M-MQTT.md](measurements/M-MQTT.md). **MQTT 단일 Paho 콜백 스레드가 인입을 ~3.25K/s로 묶는다. 같은 stream 스토리지가 HTTP로는 ~9.7K/s(디스크 65%)를 같은 날 처리 → 병목은 적재가 아니라 수집 어댑터의 인입(3배 차).** 저인입이라 스토리지가 28행 작은 배치로 커밋 남발(~100/s ≈ HDD fsync 천장) → 디스크 90%는 스토리지 한계가 아니라 저인입 증상(대조군 HTTP로 격리). 내부 무손실(received=inserted=DB, dropped=0)·연결 1만 유지·초과분 브로커 드롭. **런2 측정 완료(2026-07-04)**: 인입 오프로드(`worker-threads` 0→8)로 **인입 3.25K→7.4K(2.3배)**, 처리량 2.3배인데 디스크 90→80%로 낮아짐(배치 28→156행·커밋 102→48/s·브로커drop 6.7K→2.7K). "디스크 90%=저인입 증상" 개선으로 확증. at-least-once 유지·내부 무손실·OOM 없음(8GB). **M-MQTT 병목 규명+개선 완결(2026-07-04)**: 인입 병렬화 2슬라이스로 **3.25K→~9K(2.8배), HTTP 스토리지 천장(9.7K) 근접·디스크 80%** → 인입은 더 이상 병목 아님. ① worker 0→8(스레드 오프로드): 3.25K→7.4K ② connections 1→4(shared subscription): 7.4K→~9K. 배치 28→156→388·커밋 102→48→24·브로커drop 6.7K→2.7K→1.3K. shared-sub Mosquitto+Paho3 작동 실증(CI). 남은 갭(~9K vs 9.7K)은 스토리지 축(M2-par/M4b 영역), 인입 아님. 상세 [M-MQTT.md](measurements/M-MQTT.md). **팔로업(보류)**: 프로덕션 기본값(worker-threads·connections 현재 0/1=현행 보존) 확정은 배포 시점에. **천장 탐색 완료(capacity sweep, 2026-07-04)**: 부하 20K로 올려 재니 **두 경로 다 ~10K에서 막히고 한계는 스토리지가 아니었다.** 스토리지@workers4는 ~10K를 디스크 60~68%로 처리(외삽 ~15K 여유). HTTP ~10K=k6(Colima) 한계, MQTT ~9.8K(conn8@10K)=**mosquitto 단일 스레드**. 판별: offer 20K서 conn4·conn8 둘 다 붕괴(~4K·드롭 16K/s), offer 10K서 conn8=9.8K(드롭 235/s) → 연결 무관, **브로커 혼잡 붕괴**(내 "앱 과병렬" 가설 반증). MQTT 10K 초과=브로커 스케일(클러스터/EMQX/HiveMQ, ADR 0004 게이트), 앱·스토리지 아님. **측정위생 발견**: capacity 절대수치는 판마다 `down -v`(TRUNCATE만이면 세션 후반 ~6K 퇴행 실측). 상세 [M-MQTT.md §천장](measurements/M-MQTT.md). 부하도구 함정(template:// 파일모드·`%TIMESTAMPMS%`·`--ulimit`·`docker kill`·브로커 nofile 1M)은 `load/mqtt-*`·M-MQTT.md에 기록. |
| **메모 / 보류** | **M4b-A 시작점(데모 발견, 2026-07-02)**: 10k 실시간 관제의 병목은 파이프라인이 아니라 **관제 화면** — 스냅샷 `GET /latest` 10k=**4.87MB/0.5s**(서버측 정상)이나 브라우저가 **마커 1만 개 DOM 렌더**에서 멈춤. 개선 후보: 경량 렌더셋(→~300KB) + canvas/클러스터/뷰포트 컬링. // **검증 규율**: 새 서브시스템은 통합테스트 동반, CI green까지 완료 선언 금지. SLO: 업링크 10k·조회 1만·다운링크 ~500. |

---

## 포지셔닝 (2026-06-30 도메인 포지셔닝으로 갱신)

**도메인: IoT/센서 텔레메트리 시계열 파이프라인** + 측정 주도 정체성 유지. 상세 SLO는 [ROADMAP §목표 SLO](ROADMAP.md).

- **핵심(깊게):** 적재 성능(M0→M1→**M2 TimescaleDB**) · 실시간(**M4 Redis+WebSocket**) · 수집 프로토콜(**M-MQTT**)
- **보조:** M3 추상화 · M5 지오펜스 · M6 보안 · **M8 k8s** · 페이즈2 정합성
- **다루지 않음:** GC/메모리 튜닝(I/O가 병목이라 비병목 — 측정 근거)

**목표 데이터 흐름 ([ADR 0007](decisions/0007-messaging-storage-redis-streams-and-governance.md)·[0008](decisions/0008-telemetry-store-timescaledb.md)):**
```
Device ─HTTP/MQTT→ [수집/배치] → TimescaleDB 하이퍼테이블 (순차 저장, 보존·압축 내장)
   (M4~) Redis Stream fan-out: ├ storage  ├ monitoring→WebSocket 푸시  └ geofence
   Redis 캐시: 디바이스별 최신상태 (실시간 지도, 1만 대)
```
- **수집 전송 = HTTP + MQTT**(다른 계층) · **fan-out = Redis Streams**(Kafka 보류) · **저장 = TimescaleDB**(MySQL에서 전환, M1 측정 트리거).
- **DeviceType이 데이터 도달범위+보존을 가른다** — 폰은 장기 경로 **구조상 없음**(TimescaleDB retention으로 구현).

---

## 결정 로그 (되돌리기 싼 결정은 ROADMAP 보류표, 확정은 해당 문서 + 여기 기록)

| 날짜 | 결정 | 어디에 |
|---|---|---|
| 2026-06-28 | **성능 헤드라인 우선**(쓰기/읽기 두 경로 대상, 나머지 보조) | 이 문서 §포지셔닝 |
| 2026-06-28 | **M1 = fsync 분할 실험**(배치 insert·flush 설정·그룹 커밋, 결정적 지표=fsync/req) | [`M1.md`](measurements/M1.md) |
| 2026-06-28 | **M2 = 인메모리 큐 배치**(외부 브로커 0). 최대 처리량은 싱크가 올린다, 큐 아님 | ROADMAP 매핑표 |
| 2026-06-28 | **메시징/저장 아키텍처 확정**: fan-out 브로커=**Redis Streams**(Kafka 아님) · DeviceType별 보존·도달범위 · 리플레이 2종(운영/도메인) · 폰 장기경로 구조상 없음 | **[ADR 0007](decisions/0007-messaging-storage-redis-streams-and-governance.md)** |
| 2026-06-28 | Kafka는 **Redis Streams 못 버틸 때**의 측정-게이트 전환(단계 ③) | [ADR 0007](decisions/0007-messaging-storage-redis-streams-and-governance.md) |
| 2026-06-29 | **STATUS 하네스 기계화** — pre-commit 훅이 실질 변경 시 STATUS 동반 갱신 강제 | `.githooks/pre-commit`, CLAUDE §7, build.gradle.kts |
| 2026-06-30 | **도메인 포지셔닝**(IoT/센서 시계열 파이프라인). 로드맵 재정렬, GC 튜닝 제외(비병목), 목표 SLO 명시 | [ROADMAP §SLO](ROADMAP.md) |
| 2026-06-30 | **텔레메트리 저장소 TimescaleDB 전환** — M1 디스크 병목이 트리거. 순차 저장=PostgreSQL+시계열+병목 해결 | **[ADR 0008](decisions/0008-telemetry-store-timescaledb.md)** |
| 2026-06-30 | **MQTT 수집 추가**(IoT 표준 전송). Redis Streams(fan-out)와 다른 계층, 공존. Kafka는 보류 유지 | [ADR 0007 §MQTT](decisions/0007-messaging-storage-redis-streams-and-governance.md) |
| 2026-06-30 | **문서 작성 규칙 강화** — 비유·직역체 조어·장식 이모지·지어낸 서사 금지를 §7에 codify | [conventions.md §7](conventions.md) |
| 2026-06-30 | **M2 = 앱 전체 PostgreSQL/TimescaleDB 단일 교체**(MySQL 제거). 대안(Influx·ClickHouse·QuestDB·Cassandra·순수 PG) 검토 — 관계형+시계열 한 엔진이 기준. 기대 효과·기각 근거 ADR에 정리 | **[ADR 0008](decisions/0008-telemetry-store-timescaledb.md)** |
| 2026-07-02 | **M4b(B) stream 적재 측정 확정**: `stream-maxlen` 기본 1M→**400K**(256mb 무손실 최소값). 스트림=append-only 로그(XACK≠삭제)라 MAXLEN을 maxmemory에서 역산·worst-case 랙보다 크게. 지속 10k 무손실(워커4), 재시작 at-least-once 실증. 폭주=blind MAXLEN(내구 무손실) | **[M4b.md](measurements/M4b.md)**, `application.yml`, `IngestProperties` |
| 2026-07-03 | **결정 문서 전면 재검토** — ADR 0008 재작성(M1이 확정한 것 → 전환 검증(효과 실측) → 검증하지 않은 대안(PK 재설계+파티셔닝·MyRocks) → 선택 근거(워크로드 정렬·운영 자동화). 저장소 교체가 측정에서 필수로 따라 나온 것이 아님을 명시, PK 실험을 미해결로). 0007 갱신(MAXLEN 사이징=M4b 실측 반영, 폰 "구조로 해결"→장기 아카이브 한정). 0001~0004 서술 정확화. 보류표 시점 재설정(FK→필요 시, 인증→가칭 M4c 또는 M6) + STRUCTURE·conventions·보드 낡음 갱신 + 문서 전수 표현 정리 | ADR 0001~0008, ROADMAP, STRUCTURE, conventions, CLAUDE §2.1, PERFORMANCE, measurements |
| 2026-07-03 | **문서 표현 규칙 확장** — 의인화("측정이 말한다")·콜론 뒤 선언 수사·자기 평가 라벨("(정직 기록)")·관념어/평가어("필연·승리") 금지, 두괄식, 라벨에 해석 금지를 §7에 추가 | [conventions.md §7](conventions.md) |
| 2026-07-03 | **MQTT 토픽 구조 = `telemetry/{deviceId}`**(identity는 토픽에, 구독 `telemetry/+`). 근거: 브로커 ACL·per-device last-will·스푸핑 방지는 토픽 identity가 전제(Azure IoT Hub 구조 강제·mosquitto ACL pattern으로 확인), 토픽은 디바이스 계약이라 클라이언트가 시뮬레이터뿐인 지금이 변경 비용 최소. 페이로드 deviceId 생략 가능·불일치 drop. 계기=emqtt-bench 페이로드 템플릿 제약(도구 독립 근거 확인 후 결정). **부하 도구=emqtt-bench(공식, Docker)** — xk6-mqtt는 README가 POC·미지원 명시라 기각, 자작 퍼블리셔는 폴백 | [ADR 0007 §MQTT](decisions/0007-messaging-storage-redis-streams-and-governance.md), `MqttTelemetryHandler` |
| 2026-07-02 | **M-MQTT 클라이언트 = Eclipse Paho v3 직접 + `MqttSubscriber` 이음새(ADR 0004)**. 단순 구독→적재라 Spring Integration은 과추상(라우팅 파이프라인 계획 없음). 고처리량에서 리액티브·MQTT5·backpressure 필요 시 **HiveMQ 구현체 추가**로 전환(재작성 아님, 측정 게이트). 불안정 네트워크(재접속·QoS·last-will) 직접 통제 | `app.telemetry`(`MqttSubscriber`/`PahoMqttSubscriber`), [ADR 0007 §MQTT](decisions/0007-messaging-storage-redis-streams-and-governance.md) |
| 2026-07-04 | **M-MQTT 천장 = 스토리지 아니라 전송 경로 ~10K**. 부하 20K로 재니 HTTP·MQTT 둘 다 ~10K서 막힘, 스토리지@workers4는 디스크 60~68%(외삽 ~15K 여유). HTTP=k6(Colima) 한계, MQTT ~9.8K=**mosquitto 단일 스레드**. 판별(conn4·conn8 × offer 10K·20K): offer 20K서만 붕괴(연결 무관)=브로커 혼잡 붕괴("앱 과병렬" 가설 반증). MQTT 10K 초과=브로커 스케일(EMQX/HiveMQ, ADR 0004). 측정위생: capacity는 판마다 `down -v`(TRUNCATE만이면 세션 퇴행 ~6K 실측) | [M-MQTT.md §천장](measurements/M-MQTT.md) |
| 2026-07-04 | **M-MQTT 런2 2번째 슬라이스 = 다중 연결(shared subscription)로 천장 근접**. connections 1→4(worker8 유지): 7.4K→~9K, HTTP 스토리지 천장(9.7K) 근접·디스크 80%. 배치 156→388·커밋 48→24·브로커drop 2.7K→1.3K. Mosquitto+Paho3 shared-sub 작동 실증("MQTT5=HiveMQ 게이트" 반증). 전체 3.25K→~9K(2.8배)로 인입 병목 제거, 남은 갭은 스토리지 축. M-MQTT 완결 | [M-MQTT.md](measurements/M-MQTT.md) |
| 2026-07-04 | **M-MQTT 런2 = 인입 오프로드로 병목 개선 실증**. `worker-threads` 0→8: 인입 3.25K→7.4K(2.3배), 처리량 2.3배인데 디스크 90→80%↓(배치 28→156행→커밋 102→48/s→fsync↓). "디스크 90%=저인입 증상, 스토리지 한계 아님"이 개선으로 확증. 매뉴얼 ack로 at-least-once 유지. 7.4K서 평평·디스크 80% 여유 → 다음 병목=단일 구독 연결(QoS1 인플라이트), HTTP 9.7K 갭 | [M-MQTT.md](measurements/M-MQTT.md) |
| 2026-07-03 | **M-MQTT 병목 = 수집 어댑터 인입(단일 콜백 스레드)**. MQTT ~3.25K/s인데 같은 stream 스토리지가 HTTP로는 ~9.7K/s(디스크 65%)를 같은 날 처리(3배). 저인입→28행 작은 배치→~100커밋/s≈HDD fsync 천장→디스크 90%(스토리지 한계 아니라 저인입 증상, HTTP 대조군으로 격리). 오판 2건 기록: "디스크 바쁨=한계" 오인·`maxDelayMs`로 배치 못 키움(XREADGROUP 트리클 즉시 반환). 개선(인입 병렬화→9.7K)은 런2 미측정 | [M-MQTT.md](measurements/M-MQTT.md) |
| 2026-07-03 | **M-MQTT 측정 = `mode=stream` 고정 + 3경계 손실 오라클**. stream 근거: 목표 경로이고 하류는 M4b가 무손실 특성화라 변수를 인입 계층(단일 Paho 콜백 스레드)으로 좁힘. `direct`는 fsync 병목이라 M1 재측정. 오라클을 등식 하나(`received−dropped==DB`)가 아니라 브로커 도달 R vs 발행 P·어댑터 드롭·스트림 트림/dedup 3경계 정산으로 — 큐 offer-drop(`ingest.dropped`)·MAXLEN 트림·QoS1 재전송 dedup이 `mqtt.dropped`로 안 흘러와 accepted를 과대계상하기 때문. stream·QoS1·MAXLEN 400K·1만 디바이스×1Hz로 트림·dedup 0 확보 | [measurements/M-MQTT.md](measurements/M-MQTT.md) |
| 2026-07-03 | **수집 봉투 숫자 timestamp = epoch 밀리초**(`read-date-timestamps-as-nanoseconds=false`). IoT 디바이스 시각 관례가 epoch millis. ISO 문자열은 영향 없음(HTTP 그대로). Jackson 기본(초 해석)이면 ms 입력이 서기 5만 년→`@ValidTimestamp` 전량 드롭. 가드=`TelemetryRequestJsonTest`(두 형식 고정). 계기=emqtt-bench `%TIMESTAMP%`(ms) 스모크 | `application.yml`, `TelemetryRequestJsonTest` |
| 2026-07-01 | **M4 읽기경로 스펙 확정 + M4 분해**(M4a 캐시 → M4b Streams+push → 인증 별도). 신선도=**push**(폴링 대체) · 스코프=**조직**(device→org 1:N `orgId`, 관리자↔org M:N·권한강제는 인증 보류) · 오프라인=**지도 유지**(lastSeen 나이 파생, TTL 만료 청소·M:N device-org 기각) | **[M4 스펙](specs/M4-realtime-read-path.md)** |

---

## 📋 마일스톤 보드 (한눈에)

| M | 주제 | 상태 | 핵심 기술 | 추가 인프라 |
|---|---|---|---|---|
| **M0** | 모델·수집·조회·시뮬레이터·측정 | ✅ (`m0` 태그) | Java·REST | MySQL |
| **M1** | 적재 포화점 높이기(fsync 분할) | ✅ (배치로 ~44×) | 성능 측정 | — |
| **M2** | **TimescaleDB 전환**(순차 저장) | ✅ (~3.8× → 지속 10k 무손실) | PostgreSQL·시계열 | TimescaleDB |
| **M4** | **실시간: 읽기경로 + Streams fan-out** | ✅ 코어 — M4a(읽기 ~250×)·M4b(지속 10k 무손실·재시작 at-least-once). 잔여: M4b-A(push 측정·관제 화면 스케일링)·인증(가칭 M4c) | WebSocket | Redis |
| **M-MQTT** | **MQTT 수집 경로** | ✅ 수집+측정+개선 완결. 인입 병렬화(스레드+연결)로 3.25K→~9K(2.8배)·HTTP 천장 근접, 인입 병목 제거 | MQTT | Mosquitto |
| **M3** | 추상화 검증(디바이스 타입) | ✅ 봉투 일반화 + AMR 추가로 core diff=enum 1줄+게이트 훅뿐 실증(engine·엔티티 불변). 폰 프라이버시 게이트 불변 | 양축 추상화 | — |
| **M5** | 도달/이탈 판정(geofence) | ⬜ | — | (Redis Stream CG) |
| **M6** | 민감정보 보호·보존 | ⬜ | — | (TimescaleDB retention) |
| **M7** | 대용량 조회·복제 | ⬜ | — | (TimescaleDB 하이퍼테이블) |
| **M8** | 컨테이너·**k8s** | ⬜ | k8s | (앱 컨테이너화) |
| **M9~M11** | 페이즈2(다운링크/미션) | ⏸ | 정합성 | — |

---

## M0 — 모델·수집·조회·시뮬레이터·측정  ✅ (`m0` 태그)  쓰기경로 baseline
### 기반
- [x] 프로젝트 스캐폴딩 (Gradle Kotlin DSL, Java 21, Spotless, **ArchUnit core 경계**, CI)
- [x] ADR·STRUCTURE·ROADMAP·conventions·SECURITY 문서
- [x] **STATUS 하네스** (CLAUDE.md §7 — 모든 작업·결정을 STATUS에 반영, 커밋과 동기화)
### 도메인·수집 (쓰기)
- [x] 도메인 모델 (`Telemetry`, `Device`, `Location`, enums)
- [x] 수집 API `POST /api/telemetry` (Bean Validation + `@ValidTimestamp`) — 202 Accepted(M2 비동기 호환)
- [x] 최소수집 게이트 (permission DENIED / sharing off → 위치 미수집)
- [x] `Device` upsert (deviceId 기준, FK 없이 느슨 연결) — ⚠️ 매 요청 트랜잭션 내 UPDATE = M1 교란변수(A2-x로 격리)
### 조회 (읽기) — 수직 슬라이스 닫기 (2026-06-22)
- [x] `GET /api/telemetry/{id}/latest` (단건 최신, 없으면 404)
- [x] `GET /api/telemetry/{id}` (이력, offset 페이징 — 커서는 M7)
- [x] `GET /api/telemetry/latest` (디바이스별 최신 — **naive 상관 서브쿼리**, M4 Redis 캐시 *before* 대상)
- [x] 관제 웹 지도 (`static/index.html`, Leaflet+OSM, 폴링) — 실시간 푸시는 M4
- [x] 테스트: WebMvc(GET) + 통합(실 MySQL, latest-per-device 정확성), CI green
### 시뮬레이터·측정 인프라
- [x] 시뮬레이터 (가상스레드 1디바이스=1스레드, random walk)
- [x] Actuator → Prometheus 메트릭 노출 (p95/p99·GC·HikariCP)
- [x] k6 스크립트: `baseline`(닫힌) · `stress`(닫힌 모델 포화점) · `capacity`(열린, 1Hz 디바이스)
- [x] RUNBOOK(2머신) · monitoring compose(Prometheus/Grafana) · run-app.sh
### 측정 환경 구축 (2026-06-21~22)
- [x] 박스(우분투): JDK21 · Docker · Locus MySQL(docker 3307, 시스템 MySQL과 공존) · 앱 8093(tomcat9가 8080 점유) · 3/4 자원 격리(cpuset 0-5)
- [x] nginx 공개 경로 `locus.thdwjdrl.com` (TLS→8093)
- [x] 맥: k6 · Prometheus · Grafana (타깃 UP 확인)
### 측정 (2026-06-29 capacity·병목 확증 완료)
- [x] **닫힌 baseline 3회 중앙값** (50VU, 2026-06-29): **36.5 req/s, p95 1.63s**, 에러 0% (닫힌>열린: group commit 효과)
- [x] 환경 보강: RTT avg 0.81ms, 시작 행수 34,987
- [x] **capacity 측정** — 포화점 **≈ 33** 1Hz 디바이스 (달성 처리량이 4회 런 모두 ~33으로 견고; 닫힌 32.9 포함). p95는 최대 처리량 초과 여부·정착 상태에 따라 0.96s~30s로 변동 = 백로그로 인한 현상
- [x] **병목 확증 = HDD fsync 3중 증거**: CPU 유휴(2~8%) + HikariCP pending + 디스크 %util 97%·f_await 25ms·f/s 39(iostat 194샘플 평균) → **최대 처리량이 계산으로 설명됨**(40 fsync/s ÷ ~1.2 fsync/req ≈ 33)
- [x] [`measurements/M0.md`](measurements/M0.md) 수치·해석·스크린샷(`img/`) 기록 완성 + 원본 데이터 `M0-raw/`
- [x] **커밋 + `m0` 태그** (`9e3142b` 측정 + `85170d5` 문서정리, 태그 재지정. 미푸시)

---

## M1 — 적재 최대 처리량 높이기: fsync 분할  ✅  쓰기경로
> 설계 완료 → [`measurements/M1.md`](measurements/M1.md). M0 병목(`단건 insert + 커밋당 fsync → ~33 req/s`)을 **MySQL 안에서 먼저** 개선한다. Kafka 없음.
> **결정적 지표 = 요청당 fsync 횟수**(`Innodb_data_fsyncs` 델타 ÷ 요청 수) — 이게 줄며 최대 처리량 오르면 "병목=fsync" 인과 증명.
- [x] 실험 *설계* 완료 (A0~A3·A2-x 비교군, 절차, 예상결과)
- [x] **A1 측정** (flush=2): 포화점 33→**66**(~2×), log fsync/req 1.0→0.05 → **fsync 병목 인과 증명**. 원본 `M1-raw/`
- [x] **A2 구현 완료**: `TelemetryIngestPort`(ADR 0004) + 인메모리 큐 + 배치 워커(SmartLifecycle) + `TelemetryBatchDao`(`JdbcTemplate.batchUpdate`). `mode=direct(기본)|queue`. green
- [x] **A2 측정**: 포화점 **~1,437 req/s (A0 대비 ~44×)**, 내구성 유지(flush=1). data fsync/req 1.2→0.059. 3런(A2/A2b/A2c)으로 포화점 확정. 원본 `M1-raw/`
- [x] **A2-x 측정**: device-upsert OFF → 포화점 1,433(≈동일) → upsert는 교란 아님(batched upsert 효율적)
- [x] **A3 측정**: 배치+flush=2 → 포화점 1,418(≈동일) → 배치 후 flush 무의미, redo 로그는 더는 병목 아님. **내구성 유지(A2) 최종 채택**. flush=1 복귀 확인
- [x] M1.md 전체 기록 + 표준 용어 정리. (A2 measurements는 flush=1이었음 데이터로 검증)
- **게이트:** "배치만으로 최대 처리량 X배 + 그 한계(크래시 유실·DB다운·백프레셔)" 측정 → M2 정당화. 병목 이동 시 다음 후보(GC/HikariCP).

## M2 — TimescaleDB 전환 (순차 저장)  ✅  쓰기경로
> M1 잔여 병목 = data fsync(InnoDB B-tree 랜덤 쓰기). 순차 쓰기로 푼다 = TimescaleDB 하이퍼테이블([ADR 0008](decisions/0008-telemetry-store-timescaledb.md)). 앱 전체 PostgreSQL 단일 교체, MySQL 제거.
- [x] **코드 이식 완료**(`test`+`check` green): postgresql 드라이버·Flyway 도입 / Telemetry 복합 PK(device_id, recorded_at) `@IdClass` / `TelemetryBatchDao` `ON CONFLICT`+jsonb(`Types.OTHER`) / `DirectIngestWriter` `persist()`(409 보존) / docker-compose timescaledb / Testcontainers PG. core infra-free 유지(ArchUnit).
- [x] **측정 완료(2026-06-30)**: 포화점 **1,437 → 5,459 rows/s(~3.8×)**, durable(synchronous_commit=on). 디스크 피크 **59%(미포화)** · 평균 쓰기 **200KB(순차)** → **랜덤→순차 확증, 디스크 병목 해소**. 포화=큐 9,000/10,000+드롭(우아). p95 654µs·0%. [M2.md](measurements/M2.md).
- [x] **병목 이동 → 단일 배치 워커**(HikariCP active=1, 디스크 여유). 다음 마일스톤(워커 병렬화)으로 연결.
- [x] **측정 함정 기록**: ~2,300 "붕괴"는 k6 `recorded_at=base+i` 미래 표류 → `@ValidTimestamp(60s)` 400. Prometheus status별 조회로 진단, 스크립트 실제시각으로 수정(서버 정상). 잘못 판단해 넣었던 Tomcat 상향(a83c4a2)은 무해라 유지.
- [x] `shared_buffers=2GB`·`shared_preload_libraries=timescaledb` compose 고정 · 디스크 메트릭 node_exporter→Grafana(iostat 로그 폐기).
- FK ON/OFF 처리량 측정은 보류([ROADMAP](ROADMAP.md)).

## M2-par — 배치 워커 병렬화 + 적재 무손실 용량  ✅  쓰기경로
> M2에서 병목이 단일 배치 워커로 이동. 워커 병렬화 + 큐 사이징으로 단일 HDD에서 도착 10k 무손실 달성, 다음 병목까지 매핑. 전체: [`docs/measurements/M2-par.md`](measurements/M2-par.md).
- [x] **코드**(`test` green): `TelemetryBatchWorker` 단일→N 스레드(`INGEST_WORKERS`, 기본 1) + HikariCP `DB_POOL_MAX`. 공유 큐 concurrent drainTo, 워커당 커넥션 1. 기본 1이라 M2 동작 보존.
- [x] **워커 병렬화 = group commit 스케일**: append-only 평탄 N=6 9.5k → N=12 12.8k → N=16 14.4k(디스크 순차 대역폭 수렴). 단일 워커 병목 해소.
- [x] **둘째 병목 = device 행 락**: device upsert on은 워커 스케일 막힘(N=12 off 12.8k/디스크100% vs on 9k/디스크80%). device-on @10k는 큐 200k도 가득 차고 드롭(drain<10k 지속 deficit) → **device 분리(M4)가 10k에 필수** 측정 확정.
- [x] **데드락 발견·수정**: 다중 워커 device upsert 도착순 락(LinkedHashMap) → `deadlock detected`. `TreeMap` device_id 정렬로 락 순서 통일(수정, `test` green).
- [x] **10k 무손실 달성**: append-only N=16 + **큐 200k**(체크포인트 스톨 backlog ~12k 흡수) → 도착 10k dropped/s=0, durable, 단일 5400rpm HDD. 드롭 원인=배치 큐 용량(흡수 부족), 큐 사이징=worst스톨×유입×1.5~2.
- [x] **폐기 시도**: bgwriter 트리클(포화 디스크에서 WAL 경합 역효과, p95 3.5→199ms) / `checkpoint_timeout=15min`(테스트 창 밖으로 스톨 밀어내는 측정 함정) / 잘못 짚은 가설(CPU 과구독·N=8 과병렬·램프피크 조기 SLO 선언) → 평탄·격리 측정으로 정정.
- **게이트:** ✅ 단일 HDD 도착 10k 무손실(append-only) 측정 기록. SLO 10k 실경로화 = M4(device 상태 Redis 분리 + Streams 내구 버퍼).

## M3 — 추상화 검증 (디바이스 타입 추가)  ✅  보조(집 불필요·즉시 가능)
- [x] 수집 봉투 디바이스 무관 재설계 — 공통칸(deviceId/deviceType/timestamp/location) + `metrics`(JSONB 자유칸). 폰 전용 필드(battery/network/activity/appState/permission/sharingEnabled)를 `metrics`로 이동
- [x] 최소수집 게이트를 `DeviceTypeHandler.gate()`(전략)로 이동 — 폰은 permission=DENIED/sharing off면 위치 null(프라이버시 불변, 테스트로 못박음), 로봇은 게이트 없음(기본=항상 수집)
- [x] 둘째 타입 `AMR` 추가 시 **`core` diff = enum 값 1줄 + 게이트 훅 1개뿐** 확인 (`git diff --stat core/`: DeviceType·DeviceTypeHandler만, engine·엔티티 불변 — 양축 추상화 검증, CLAUDE.md §2.2). 동작은 전부 app(`AmrHandler`/`AmrMetrics`/`AmrProfile`)
- [x] AMR 검증(estop/service/charging인데 driving 모순 드롭)·시뮬(웨이포인트 odom→lat/lng 변환·저전력 충전 복귀)·통합테스트(발행→저장·모순 드롭). 참조자료 `docs/reference/amr-telemetry.md`(ROS2 common_interfaces·VDA5050 근거). 단위·ArchUnit green(60 tests)

## M4 — 실시간 푸시 · 최신상태 캐시 · 인증/식별 (Redis)  ⬜  읽기경로(+보조)
> Redis 하나가 **캐시 + Streams 브로커** 두 역할 → 새 인프라 0, §3.4 안 깸 ([ADR 0007](decisions/0007-messaging-storage-redis-streams-and-governance.md)). **읽기경로 스펙 확정 → [M4 스펙](specs/M4-realtime-read-path.md)** (신선도=push · 스코프=조직 1:N · 오프라인 유지). 세 조각으로 분해:
### M4a — 최신상태 캐시 (읽기경로 헤드라인)
- [ ] `LatestStateLookup` 포트 + **Redis 캐시** (naive 상관 서브쿼리 *before/after*, 디바이스 수별 p95/p99) — 조직 파티션 키(`latest:{orgId}`), 배치 워커 write-through
### M4b — Streams fan-out + WebSocket push
- [ ] **Redis Streams 도입** — 인메모리 큐(M2) → Stream `storage` CG + `monitoring` CG. `XACK`/`XPENDING`/`XCLAIM`, 컨슈머 멱등성
- [ ] WebSocket 실시간 push (지도 폴링 → push) ← **두 번째 소비자**(`monitoring` CG). 조직 스코프 구독, 오프라인 lastSeen 파생
### 인증(별도 — 측정 주도 아님)
- [ ] 인증/식별 (`app.auth`/`app.user`, 공통 Principal, Device≠User — [보류 결정](ROADMAP.md))
- [ ] 디바이스 그루핑 권한 강제 (관리자↔조직 M:N, `GET /api/devices` 스코프 필터) ← 조직 데이터모델(`orgId`)은 M4a에서

## M5 — 도달/이탈 판정 엔진 (geofence)  ⬜  보조
- [ ] `core.engine` 판정(미션·타입 모름) + `GeofenceStateStore`
- [ ] 텔레메트리 Stream의 `geofence` Consumer Group으로 판정 엔진 fan-out ([ADR 0007](decisions/0007-messaging-storage-redis-streams-and-governance.md))

## M6 — 민감정보 보호 · 보존  ⬜  보조
- [ ] 위치 암호화 컬럼 · 로그/덤프 평문 차단
- [ ] **보존 정책**(raw TTL + 폰 강제삭제/익명화) — DeviceType별 거버넌스, 미성년 위치 영구보관 금지 ([ADR 0007](decisions/0007-messaging-storage-redis-streams-and-governance.md))
- [ ] **버퍼 PII 점검** — Redis 영속화(RDB/AOF)·백업에 위치 묻어가는지, `XACK`/`XDEL` 즉시 정리 (ADR 0007 §점검)

## M7 — 대용량 조회 · 복제  ⬜  읽기경로
- [ ] recorded_at **시간 파티셔닝** + 커서 페이징 (오래된 파티션 drop = 보존 삭제 비용 ≈ 0)
- [ ] MySQL 읽기 복제 · 라우팅 데이터소스

## M8 — 컨테이너 · k8s  ⬜  보조
- [ ] Dockerfile · 이미지 빌드(CI) → 컨테이너화 비용 before/after
- [ ] (선택) CD 자동화는 마일스톤 밖 — [보류 결정](ROADMAP.md)

## 페이즈 2 (다운링크/미션) — M9~M11  ⏸
- [ ] M9 명령 경로 + 미션 도메인 (ack·멱등·TTL·순서·fail-safe)
- [ ] M9 **Mission Archive**(도메인 리플레이, 장기 보존 — **로봇만, 폰 경로 없음**, [ADR 0007](decisions/0007-messaging-storage-redis-streams-and-governance.md))
- [ ] M10 미션 동시성·정합성 (락 전략 비교)
- [ ] M11 (선택) 미션 타입 추가 / MQTT

---

## ❓ 미해결 결정 (확정 필요 — ADR 0007 §미해결)
- [ ] raw telemetry **보존 기간(TTL) 수치** 확정 (M6)
- [ ] **Mission Archive를 raw에서 어떻게 뽑나** — Stream 컨슈머 직접 적재 vs 배치 집계 (MissionType 설계와 엮임, 페이즈2)
- [ ] 로봇 **Mission Archive 저장소** — MySQL 시계열 테이블 vs 별도 시계열/오브젝트 스토리지
