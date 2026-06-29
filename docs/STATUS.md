# 작업 현황 (STATUS) — 진행의 단일 진실원

> **이 문서 = 살아있는 진행 트래커** (뭘 끝냈고 / 지금 뭐 하고 / 다음에 뭐 할지 / 왜 그렇게 정했는지의 흔적).
> 역할 분담 — 이 문서는 **상태**만. *왜·어디에* = [ROADMAP](ROADMAP.md), *수치·해석* = [measurements/](measurements/), *결정 이유(ADR)* = [decisions/](decisions/), *트리 매핑* = [STRUCTURE](STRUCTURE.md).
> 범례: ✅ 완료 · 🔄 진행 중 · ⬜ 예정 · ⏸ 보류(다른 마일스톤) · 🚧 막힘(외부의존). 작업·결정 끝낼 때마다 여기 갱신(CLAUDE.md §7).

---

## 🎯 현재 포커스 — M1 A1 설정 실험

| | |
|---|---|
| **한 줄** | **M1 A2 측정 완료(2026-06-29)**: 배치로 적재 포화점 **33→~1,437 req/s (~44×), 내구성 유지**(flush=1). M1 핵심 결론 확보. 남은 건 A3·A2-x. |
| **방금 끝낸 것** | A2 측정 3런 → 포화점 ~1,437(드롭·큐만석·처리량평탄 동시). fsync/req 1.2→0.059. [`M1.md`](measurements/M1.md) 기록 + 원본 `M1-raw/`. |
| **다음 한 걸음** | 집·박스: A3(`SET GLOBAL flush=2` + queue) / A2-x(`INGEST_DEVICE_UPSERT=false`) 측정 → M1.md 표 마무리 → `m1` 태그 검토. |
| **메모** | A2 포화점에서 CPU 처음 ~0.10(병목 이동 조짐). A2 한계(앱 크래시 시 큐·드롭 유실)는 M4 Redis Streams 명분. |

---

## 🧭 포지셔닝 & 헤드라인 서사 (2026-06-28 결정)

**전략: 프로젝트 우선 · 성능/백엔드 헤드라인.** 11개 마일스톤을 다 똑같이 파지 않고, **2개 경로에 깊이를 몰아** "naive → 측정 → 병목 → 재설계 → 재측정" 서사를 만든다.

- **🦴 척추(깊게):**
  - **쓰기 경로** — `M0`(baseline) → `M1`(fsync 배치) → `M2`(인메모리 큐 배치)
  - **읽기 경로** — `M4`(최신상태 Redis 캐시) → `M7`(시간 파티셔닝·커서)
- **🎬 조연(설계만/가볍게):** `M3` 추상화(하루짜리, 아키텍처 크레딧) · `M5`·`M6`·`M8`·페이즈2 — ROADMAP에 설계만 남김.

**목표 데이터 흐름(북극성) — 인프라 역할이 갈리는 곳 ([ADR 0007](decisions/0007-messaging-storage-redis-streams-and-governance.md)):**
```
Device → POST → [M2 인메모리 큐+배치] → MySQL raw (원본 이력·운영 리플레이 원천: 단기 보존)
   (두 번째 소비자 생기면, M4~) → Redis Stream 단기버퍼(MAXLEN) ─ Consumer Group fan-out:
        ├ storage   → MySQL raw          ├ monitoring → 실시간 푸시(M4)   └ geofence → 판정(M5)
   Redis 캐시(같은 Redis): 최신상태(작고·뜨겁고·휘발 → 지도)                                  ← 읽기경로(M4)
   (로봇만, 페이즈2) MySQL raw → 미션 집계 → Mission Archive(장기·도메인 리플레이). 폰=경로 없음.
```
- **브로커 = Redis Streams(Kafka 아님)** — fan-out엔 충분·가볍다. Kafka는 Streams 못 버틸 때 측정-게이트 전환. 천장은 싱크(배치)지 브로커 아님.
- **DeviceType이 데이터 도달범위+보존을 가른다** — 폰은 장기 경로가 **구조상 없음**(정책 아닌 구조로 위치 최소보존).
> 학습 목표(MQ/Redis Streams/Redis를 *써보며 배우기*)는 이 순서로 충족 — **겪고 → 도입 → 측정**. 한 번에 다 안 올리는 게 YAGNI이자 더 나은 학습 순서.

---

## 📌 결정 로그 (되돌리기 싼 결정은 ROADMAP 보류표, 확정은 해당 문서 + 여기 흔적)

| 날짜 | 결정 | 어디에 |
|---|---|---|
| 2026-06-28 | **프로젝트 우선 · 성능 헤드라인**(쓰기/읽기 두 경로 척추, 나머지 조연) | 이 문서 §포지셔닝 |
| 2026-06-28 | **M1 = fsync 분할 실험**(배치 insert·flush 설정·그룹 커밋, smoking gun=fsync/req) | [`M1.md`](measurements/M1.md) |
| 2026-06-28 | **M2 = 인메모리 큐 배치**(외부 브로커 0). 천장은 싱크가 올린다, 큐 아님 | ROADMAP 매핑표 |
| 2026-06-28 | **메시징/저장 아키텍처 확정**: fan-out 브로커=**Redis Streams**(Kafka 아님) · DeviceType별 보존·도달범위 · 리플레이 2종(운영/도메인) · 폰 장기경로 구조상 없음 | **[ADR 0007](decisions/0007-messaging-storage-redis-streams-and-governance.md)** |
| 2026-06-28 | Kafka는 **Redis Streams 못 버틸 때**의 측정-게이트 전환(사다리 ③) | [ADR 0007](decisions/0007-messaging-storage-redis-streams-and-governance.md) |
| 2026-06-29 | **STATUS 하네스 기계화** — pre-commit 훅이 실질 변경 시 STATUS 동반 갱신 강제 | `.githooks/pre-commit`, CLAUDE §7, build.gradle.kts |

---

## 📋 마일스톤 보드 (한눈에)

| M | 주제 | 상태 | 척추/조연 | 추가 인프라 |
|---|---|---|---|---|
| **M0** | 모델·수집·조회·시뮬레이터·측정 | ✅ (`m0` 태그) | 🦴 쓰기 | MySQL |
| **M1** | 적재 천장 깨기(fsync 분할) | 🔄 (A1 착수) | 🦴 쓰기 | — |
| **M2** | 배치 적재(인메모리 큐) | ⬜ | 🦴 쓰기 | — |
| **M3** | 추상화 검증(디바이스 타입) | ⬜ | 🎬 조연 | — |
| **M4** | 실시간 푸시·최신 캐시·인증 | ⬜ | 🦴 읽기(+조연) | Redis |
| **M5** | 도달/이탈 판정(geofence) | ⬜ | 🎬 조연 | (Redis Stream `geofence` CG 재사용) |
| **M6** | 민감정보 보호·보존 | ⬜ | 🎬 조연 | — |
| **M7** | 대용량 조회·복제 | ⬜ | 🦴 읽기 | MySQL 읽기복제 |
| **M8** | 컨테이너·k8s | ⬜ | 🎬 조연 | (앱 컨테이너화) |
| **M9~M11** | 페이즈2(다운링크/미션) | ⏸ | — | — |

---

## M0 — 모델·수집·조회·시뮬레이터·측정  ✅ (`m0` 태그)  🦴 쓰기경로 baseline
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
- [x] k6 스크립트: `baseline`(닫힌) · `stress`(닫힌 knee) · `capacity`(열린, 1Hz 디바이스)
- [x] RUNBOOK(2머신) · monitoring compose(Prometheus/Grafana) · run-app.sh
### 측정 환경 구축 (2026-06-21~22)
- [x] 박스(우분투): JDK21 · Docker · Locus MySQL(docker 3307, 시스템 MySQL과 공존) · 앱 8093(tomcat9가 8080 점유) · 3/4 자원 격리(cpuset 0-5)
- [x] nginx 공개 경로 `locus.thdwjdrl.com` (TLS→8093)
- [x] 맥: k6 · Prometheus · Grafana (타깃 UP 확인)
### 측정 (2026-06-29 capacity·병목 확증 완료)
- [x] **닫힌 baseline 3회 중앙값** (50VU, 2026-06-29): **36.5 req/s, p95 1.63s**, 에러 0% (닫힌>열린: group commit 효과)
- [x] 환경 보강: RTT avg 0.81ms, 시작 행수 34,987
- [x] **capacity 측정** — knee **≈ 33** 1Hz 디바이스 (달성 처리량이 4회 런 모두 ~33으로 견고; 닫힌 32.9 포함). p95는 천장 위로 미느냐·정착에 따라 0.96s~30s 출렁 = 백로그 산물
- [x] **병목 확증 = HDD fsync 3중 증거**: CPU 유휴(2~8%) + HikariCP pending + 디스크 %util 97%·f_await 25ms·f/s 39(iostat 194샘플 평균) → **천장 산수로 닫힘**(40 fsync/s ÷ ~1.2 fsync/req ≈ 33)
- [x] [`measurements/M0.md`](measurements/M0.md) 수치·해석·스크린샷(`img/`) 기록 완성 + 원본 데이터 `M0-raw/`
- [x] **커밋 + `m0` 태그** (`9e3142b` 측정 + `85170d5` 문서정리, 태그 재지정. 미푸시)

---

## M1 — 적재 천장 깨기: fsync 분할  🔄  🦴 쓰기경로
> 설계 완료 → [`measurements/M1.md`](measurements/M1.md). M0 병목(`단건 insert + 커밋당 fsync → ~33 req/s`)을 **MySQL 안에서 먼저** 짜낸다. Kafka 없음.
> **결정적 지표(smoking gun) = 요청당 fsync 횟수**(`Innodb_data_fsyncs` 델타 ÷ 요청 수) — 이게 줄며 천장 오르면 "병목=fsync" 인과 증명.
- [x] 실험 *설계* 완료 (A0~A3·A2-x 비교군, 절차, 예상결과)
- [x] **A1 측정** (flush=2): 포화점 33→**66**(~2×), log fsync/req 1.0→0.05 → **fsync 병목 인과 증명**. 원본 `M1-raw/`
- [x] **A2 구현 완료**: `TelemetryIngestPort`(ADR 0004) + 인메모리 큐 + 배치 워커(SmartLifecycle) + `TelemetryBatchDao`(`JdbcTemplate.batchUpdate`). `mode=direct(기본)|queue`. green
- [x] **A2 측정**: 포화점 **~1,437 req/s (A0 대비 ~44×)**, 내구성 유지(flush=1). data fsync/req 1.2→0.059. 3런(A2/A2b/A2c)으로 포화점 핀(드롭 78,031·큐 만석·처리량 평탄 동시). 원본 `M1-raw/`
- [ ] 🚧 **A3·A2-x 측정**(집): A3=A2+flush=2 / A2-x=device-upsert=false
- [ ] 🚧 A2 측정 — 앱 배치(크기/지연 변수) → "그냥 배치"만으로 어디까지(내구성 유지)
- [ ] 🚧 A3 측정 — A2+flush=2 합산 상한
- [ ] 🚧 A2-x 측정 — Device upsert 분리(핫패스 UPDATE 교란변수 격리)
- [ ] 🚧 병목 귀인 확증 — iostat %util + fsync/req 델타 + Grafana(CPU/HikariCP). *안 오르면* CPU/풀로 재귀인.
- **게이트:** "배치만으로 천장 X배 + 그 한계(크래시 유실·DB다운·백프레셔)" 측정 → M2 정당화. 병목 이동 시 다음 카드(GC/HikariCP).

## M2 — 배치 적재 (인메모리 큐)  ⬜  🦴 쓰기경로
> 외부 브로커 0 — 천장은 싱크(배치)가 올리지 큐가 아니다. fan-out 브로커(Redis Streams)는 두 번째 소비자 생기는 M4~ ([ADR 0007](decisions/0007-messaging-storage-redis-streams-and-governance.md)).
- [ ] 수집 출력 포트 `TelemetryIngestPort` 도입 (`InMemoryQueueIngest` → 나중 `RedisStreamIngest` 교체 이음새, [ADR 0004](decisions/0004-ports-only-at-improvement-seams.md))
- [ ] 인메모리 큐 + 배치 워커 본구현 (M1 A2 승격) → 처리량 before/after
- [ ] FK 제약 ON/OFF 처리량 측정 ([보류 결정](ROADMAP.md))
- **게이트:** 적재 처리량 X배 + 인메모리 한계(크래시 유실·DB다운) 측정 기록, **인프라 0 추가**.

## M3 — 추상화 검증 (디바이스 타입 추가)  ⬜  🎬 조연(집 불필요·즉시 가능)
- [ ] `TAG`/`ROBOT` 등 둘째 핸들러 추가 시 **`core` diff 0줄** 확인 (양축 추상화 검증, CLAUDE.md §2.2)

## M4 — 실시간 푸시 · 최신상태 캐시 · 인증/식별 (Redis)  ⬜  🦴 읽기경로(+조연)
> Redis 하나가 **캐시 + Streams 브로커** 두 일 → 새 인프라 0, §3.4 안 깸 ([ADR 0007](decisions/0007-messaging-storage-redis-streams-and-governance.md)).
- [ ] 🦴 `LatestStateLookup` 포트 + **Redis 캐시** (naive 최신조회 *before/after* — 읽기경로 헤드라인)
- [ ] **Redis Streams 도입** — 인메모리 큐(M2) → Stream `storage` CG + `monitoring` CG. `XACK`/`XPENDING`/`XCLAIM`, 컨슈머 멱등성
- [ ] WebSocket 실시간 푸시 (지도 폴링 → 푸시) ← **두 번째 소비자**(`monitoring` CG)
- [ ] 인증/식별 (`app.auth`/`app.user`, 공통 Principal, Device≠User — [보류 결정](ROADMAP.md))
- [ ] 디바이스 그루핑/스코핑

## M5 — 도달/이탈 판정 엔진 (geofence)  ⬜  🎬 조연
- [ ] `core.engine` 판정(미션·타입 모름) + `GeofenceStateStore`
- [ ] 텔레메트리 Stream의 `geofence` Consumer Group으로 판정 엔진 fan-out ([ADR 0007](decisions/0007-messaging-storage-redis-streams-and-governance.md))

## M6 — 민감정보 보호 · 보존  ⬜  🎬 조연
- [ ] 위치 암호화 컬럼 · 로그/덤프 평문 차단
- [ ] **보존 정책**(raw TTL + 폰 강제삭제/익명화) — DeviceType별 거버넌스, 미성년 위치 영구보관 금지 ([ADR 0007](decisions/0007-messaging-storage-redis-streams-and-governance.md))
- [ ] **버퍼 PII 점검** — Redis 영속화(RDB/AOF)·백업에 위치 묻어가는지, `XACK`/`XDEL` 즉시 정리 (ADR 0007 §점검)

## M7 — 대용량 조회 · 복제  ⬜  🦴 읽기경로
- [ ] recorded_at **시간 파티셔닝** + 커서 페이징 (오래된 파티션 drop = 보존 삭제 ≈ 공짜)
- [ ] MySQL 읽기 복제 · 라우팅 데이터소스

## M8 — 컨테이너 · k8s  ⬜  🎬 조연
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
