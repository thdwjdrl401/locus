# 작업 현황 (STATUS) — 진행의 단일 진실원

> **이 문서 = 살아있는 진행 트래커** (뭘 끝냈고 / 지금 뭐 하고 / 다음에 뭐 할지 / 왜 그렇게 정했는지의 기록).
> 역할 분담 — 이 문서는 **상태**만. *왜·어디에* = [ROADMAP](ROADMAP.md), *수치·해석* = [measurements/](measurements/), *결정 이유(ADR)* = [decisions/](decisions/), *트리 매핑* = [STRUCTURE](STRUCTURE.md).
> 범례: ✅ 완료 · 🔄 진행 중 · ⬜ 예정 · ⏸ 보류(다른 마일스톤) · 🚧 막힘(외부의존). 작업·결정 끝낼 때마다 여기 갱신(CLAUDE.md §7).

---

## 현재 포커스 — M2 TimescaleDB 전환

| | |
|---|---|
| **한 줄** | **방향 전환(2026-06-30)**: 도메인 포지셔닝(IoT 시계열 파이프라인)에 맞춰 로드맵 재정렬. M1(1,437)이 디스크 병목 측정 근거 → **다음 = M2 TimescaleDB 전환**(순차 저장 = PostgreSQL + 시계열 + 병목 해결). |
| **방금 끝낸 것** | README 전면 재작성(도메인 서사 + 측정 헤드라인 33→1,437). repo 민감어 세탁(히스토리 포함) 완료. M1 측정 완결. |
| **다음 한 걸음** | M2 TimescaleDB 전환 착수: 영속 계층 이식 + 적재 포화점 재측정(before=MySQL 1,437). 또는 빠른 WebSocket(M4) 먼저. + (별도) 면접 articulation 연습. |
| **메모** | GC 튜닝 폐기(I/O가 병목이라 비병목, 측정 근거). SLO: 업링크 10k·조회 1만·다운링크 ~500. 측정 정체성 유지(키워드 아닌 측정 정당화). |

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

## 📌 결정 로그 (되돌리기 싼 결정은 ROADMAP 보류표, 확정은 해당 문서 + 여기 기록)

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

---

## 📋 마일스톤 보드 (한눈에)

| M | 주제 | 상태 | 핵심 기술 | 추가 인프라 |
|---|---|---|---|---|
| **M0** | 모델·수집·조회·시뮬레이터·측정 | ✅ (`m0` 태그) | Java·REST | MySQL |
| **M1** | 적재 포화점 높이기(fsync 분할) | ✅ (배치로 ~44×) | 성능 측정 | — |
| **M2** | **TimescaleDB 전환**(순차 저장) | 🔄 다음 | PostgreSQL·시계열 | TimescaleDB |
| **M4** | **실시간: Redis 캐시 + WebSocket** | ⬜ | WebSocket | Redis |
| **M-MQTT** | **MQTT 수집 경로** | ⬜ | MQTT | MQTT broker |
| **M3** | 추상화 검증(디바이스 타입) | ⬜ | 설계 | — |
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

## M2 — 배치 적재 (인메모리 큐)  ⬜  쓰기경로
> 외부 브로커 0 — 최대 처리량은 싱크(배치)가 올리지 큐가 아니다. fan-out 브로커(Redis Streams)는 두 번째 소비자 생기는 M4~ ([ADR 0007](decisions/0007-messaging-storage-redis-streams-and-governance.md)).
- [ ] 수집 출력 포트 `TelemetryIngestPort` 도입 (`InMemoryQueueIngest` → 나중 `RedisStreamIngest` 교체 이음새, [ADR 0004](decisions/0004-ports-only-at-improvement-seams.md))
- [ ] 인메모리 큐 + 배치 워커 본구현 (M1 A2 승격) → 처리량 before/after
- [ ] FK 제약 ON/OFF 처리량 측정 ([보류 결정](ROADMAP.md))
- **게이트:** 적재 처리량 X배 + 인메모리 한계(크래시 유실·DB다운) 측정 기록, **인프라 0 추가**.

## M3 — 추상화 검증 (디바이스 타입 추가)  ⬜  보조(집 불필요·즉시 가능)
- [ ] `TAG`/`ROBOT` 등 둘째 핸들러 추가 시 **`core` diff 0줄** 확인 (양축 추상화 검증, CLAUDE.md §2.2)

## M4 — 실시간 푸시 · 최신상태 캐시 · 인증/식별 (Redis)  ⬜  읽기경로(+보조)
> Redis 하나가 **캐시 + Streams 브로커** 두 역할 → 새 인프라 0, §3.4 안 깸 ([ADR 0007](decisions/0007-messaging-storage-redis-streams-and-governance.md)).
- [ ] `LatestStateLookup` 포트 + **Redis 캐시** (naive 최신조회 *before/after* — 읽기경로 헤드라인)
- [ ] **Redis Streams 도입** — 인메모리 큐(M2) → Stream `storage` CG + `monitoring` CG. `XACK`/`XPENDING`/`XCLAIM`, 컨슈머 멱등성
- [ ] WebSocket 실시간 푸시 (지도 폴링 → 푸시) ← **두 번째 소비자**(`monitoring` CG)
- [ ] 인증/식별 (`app.auth`/`app.user`, 공통 Principal, Device≠User — [보류 결정](ROADMAP.md))
- [ ] 디바이스 그루핑/스코핑

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
