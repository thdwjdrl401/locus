# M4 실시간 읽기경로 스펙 — 최신상태 캐시 + push

- 상태: 확정(방향) — 착수 전 스펙 고정. 세부(프로토콜·캐시 구조·측정 설계)는 아래 §미정.
- 일자: 2026-07-01
- 관련: [ADR 0007](../decisions/0007-messaging-storage-redis-streams-and-governance.md)(Redis Streams·거버넌스), [ADR 0004](../decisions/0004-ports-only-at-improvement-seams.md)(포트), [ADR 0008](../decisions/0008-telemetry-store-timescaledb.md)(TimescaleDB), [ROADMAP](../ROADMAP.md), [STATUS](../STATUS.md)

## 맥락
M4는 STATUS 보드에서 "실시간: Redis 캐시 + WebSocket"으로 한 칸이지만, 실제 범위는 캐시·Streams·WebSocket·인증·그루핑을 묶은 4~5 마일스톤 분량이다. 착수 전에 **읽기경로의 요구사항을 스펙으로 고정**한다. 이 문서는 ADR 0007이 정한 메시징/저장 아키텍처를 M4 읽기경로에 구체화하고, ROADMAP이 M4로 미뤄둔 그루핑/스코핑 결정을 확정한다.

## 마일스톤 분해
M4를 세 조각으로 나눈다. 인프라는 하나씩([계획서 §11](../ROADMAP.md)) 원칙을 지키며 캐시를 먼저 세우고 push를 얹는다.

| 조각 | 범위 | 인프라 | 측정 |
|---|---|---|---|
| **M4a** | `LatestStateLookup` 포트 + Redis 최신상태 캐시 | Redis 추가(캐시 역할) | 읽기경로 헤드라인: naive 상관 서브쿼리 → 캐시, 디바이스 수별 p95/p99 |
| **M4b** | 인메모리 큐 → Redis Stream(`storage`/`monitoring` CG) + WebSocket push | (같은 Redis, Streams 역할) | 내구성/복구(at-least-once·멱등), push 지연·부하 |
| **인증(별도)** | `app.auth`/`app.user`, 공통 Principal, 권한 강제 | — | 측정 주도 아님 — 별도 마일스톤 |

- **M4a → M4b 순서 근거**: push는 접속 시 "현재 전체 상태" 스냅샷이 필요하고, 그 스냅샷 소스가 최신상태 캐시다. 즉 push는 캐시를 대체하지 않고 전제한다. M4a에서 캐시 write-through를 다는 지점(배치 워커)은 M4b에서 그 워커가 `storage` Consumer Group 소비자로 승격돼도 그대로여서, M4a 결정이 버려지지 않는다.
- **인증 분리 근거**: 인증/그루핑 권한은 before/after 수치 서사가 없다(측정 주도 아님). 보안 표면이라 측정 마일스톤에 얹으면 둘 다 흐려진다.

---

## 스펙 #1 — 신선도: push(즉시)

관제 화면은 폴링이 아니라 서버 push로 갱신한다.

**정당화** — "사람이 즉시 봐야 해서"가 아니다(1만 대 fleet 오버뷰에서 1~3초 지연은 대개 무해). 근거는:
1. 폴링은 "부하 vs stale" 트레이드오프를 못 이긴다. 간격을 줄이면 부하가 늘고 늘리면 stale해진다. push는 이 트레이드오프를 없앤다.
2. 폴링 비용은 변화율이 아니라 폴 횟수에 붙는다(관리자 N명이 주기마다 전체 조회). push는 변화가 생길 때 한 번 계산해 N명에게 fan-out — 비용이 실제 변화에 결합된다.
3. `monitoring` Consumer Group은 이미 모든 텔레메트리를 받는다. 폴링은 쓰기경로가 방금 쥔 상태를 읽기경로가 DB에 다시 묻는 낭비다. push는 그 흐름을 재사용한다.
4. 지오펜스 진입(M5)·상태 변화 알림 같은 이벤트성 기능은 push 채널을 재사용한다. 폴링으론 표현이 어렵다.

**기각 — 폴링(현재 지도 3초):** M0의 임시값이지 스펙이 아니다. 위 4개 이유로 대체.

**따름:** push는 캐시를 스냅샷 소스로 필요로 한다 → M4a(캐시)가 M4b(push)의 전제.

## 스펙 #2 — 스코프: 조직(organization)

관리자는 fleet 전체가 아니라 자기 조직 단위로 구독한다. 클라이언트당 fan-out = 스코프 크기지 fleet 크기가 아니다.

- **device → 조직 = 1:N** — `Device.orgId` 컬럼 하나. 한 디바이스는 어느 순간에도 정확히 한 조직 소속.
- **조직 = 캐시 파티션 키**(`latest:{orgId}`) + push 구독 키. 접속 시 스냅샷 = 그 조직 파티션 조회.
- **뷰포트(지도 경계) = 그 위에 얹는 성능 최적화**(조직 ∩ 화면). 조직이 크면 착안, 작으면 불필요(YAGNI). 필터 위치(클라이언트 vs 서버 GEO)는 §미정.
- **조직 이동**: 지원하되 실시간이 아니라 드문 관리 명령이다. 이동 시 캐시는 명시적으로 옛 파티션에서 제거 + 새 파티션에 삽입(`HDEL latest:{old} {id}` + `HSET latest:{new} {id}`). 비용을 읽기가 아니라 드문 이동 시점에 지불한다. 캐시는 DB에서 재빌드 가능하므로 꼬여도 복구가 싸다.

**데이터 모델 vs 권한 강제 분리:** `orgId`는 데이터 모델이라 지금 확정한다(ROADMAP이 그루핑을 M4에서 정하라 함). "인증된 관리자가 그 조직을 볼 자격이 있나"라는 권한 강제는 인증 마일스톤이 위에 씌운다 — 조직 스코핑을 얻으면서 인증은 앞당기지 않는다.

**기각 — device↔조직 M:N:** ROADMAP 보류표가 "M:N 유력"으로 적었으나, 그 M:N은 **관리자↔조직**(한 관리자가 여러 조직 관리)이지 device↔조직이 아니다. device→조직은 1:N 컬럼으로 충분하다. 관리자↔조직 M:N과 권한 강제는 인증으로 보류.

## 스펙 #3 — 오프라인 디바이스: 지도에 남긴다

보고를 멈춘 디바이스는 지도에서 사라지지 않는다. 마지막 위치 + staleness(마지막 확인 후 경과)로 남긴다. 위치 추적에서 "보고를 멈췄고 마지막으로 여기 있었다"는 온라인 디바이스보다 중요한 문제 신호(전원 소진·범위 이탈·정지)일 때가 많다.

- **오프라인 판정 = lastSeen 나이 vs 임계값** — 캐시에서 삭제하는 게 아니라 읽기/push 시점에 파생. `now - lastSeen > 임계값`이면 오프라인 상태로 표시(위치는 유지).
- **임계값은 보고 주기에 매인다.** 디바이스 타입별로 다를 수 있다(1Hz 폰 vs 저빈도 태그). 구체 수치는 §미정.
- **상태 최소 3개**: online / offline-stale / sharing-off. `sharing-off`는 `permission=DENIED`/`sharingEnabled=false`(최소수집 게이트, CLAUDE.md §3.5)로 고의 미수집 — 오프라인 문제와 구분하고, 위치 노출 여부는 [민감정보 보호(M6)](../ROADMAP.md)와 함께 정한다. 디바이스의 기존 `status` 필드에 얹는다.

**기각 — TTL 만료로 잔류 엔트리 청소:** 조직 이동 후 옛 파티션에 남은 잔류 엔트리와 정당한 오프라인은 옛 파티션 입장에서 둘 다 "갱신 멈춘 엔트리"라 TTL이 구분하지 못한다. TTL로 잔류 엔트리를 지우면 오프라인 디바이스도 같이 지워져 위 요구를 위반한다. 따라서 잔류 엔트리 청소는 명시적 HDEL(스펙 #2), 오프라인은 나이 파생으로 각각 처리한다.

**캐시 TTL의 위치:** 오프라인 표시용이 아니다. 폐기/이탈 디바이스가 영영 안 지워지는 것을 막는 orphan 안전망(길게 걸거나 안 걸고 명시적 삭제 + 드문 reconcile 백스톱). 신선도는 write-through가 즉시 유지하므로 신선도 목적의 주기적 전체 pull은 불필요하다(비싼 상관 서브쿼리를 되살리는 함정).

## 스펙 #4 — 캐시 값(렌더셋)

원칙: **캐시는 지도에서 한눈에(at-a-glance) 필요한 것만, 상세는 요청 시 DB에서.** 근거는 (1) 스냅샷 크기 — 접속 시 조직 전체를 HGETALL로 당기므로 값이 얇을수록 스냅샷·push 델타가 싸다, (2) PII 최소화 — 캐시에 담긴 만큼 Redis 영속화·백업에 포함된다.

| 구분 | 필드 |
|---|---|
| 공통 | `deviceId`, `deviceType`, `lat`, `lng`, `recordedAt`(=lastSeen) |
| 파생(write-through 계산) | `collectionState`(collecting/sharing-off), `batteryBand`(정상/저전력/위험, 마커 색) |
| 상세(DB 온디맨드) | 정확 battery.level·charging, network(type/online), accuracy, altitude/speed/heading, 이력 |

**파생 시점 원칙:**
- **읽기 시 파생** — 새 보고 없이 시간 경과로 변하는 것: 오프라인(lastSeen 나이).
- **write-through 때 계산** — 다음 보고 전까진 고정인 것: `collectionState`, `batteryBand`. 새 보고가 엔트리를 덮어쓰며 재계산.

**위치 보유의 불가피성:** 라이브 위치 지도라 캐시가 `lat`/`lng`를 담는 건 불가피하다. 즉 최신상태 캐시 = PII 보유 저장소. Redis 영속화(RDB/AOF)·백업 정책은 [민감정보 보호(M6)](../ROADMAP.md)에서 결정([ADR 0007 §버퍼 내 PII](../decisions/0007-messaging-storage-redis-streams-and-governance.md)).

**기각 — 원시 `battery.level`·`network`·`accuracy`를 캐시에:** 오버뷰에 한눈에 필요치 않고 스냅샷·PII만 키운다. 특히 `network(type/online)`은 store-and-forward(오프라인 버퍼링) 없이는 무의미하다 — 오프라인이면 전달 자체가 안 되고, 지연 프레임은 `@ValidTimestamp(60s)`가 거부한다. 라이브 오버뷰의 "살아있나"는 자가보고 `network.online`이 아니라 서버측 staleness(lastSeen)가 진짜 신호. (모델의 `network` + 이중 시각 recordedAt/receivedAt은 store-and-forward를 염두에 둔 흔적이나 현재 검증이 이를 막는다 — 버퍼링이 필요해지면 그때 @ValidTimestamp 완화 + network 의미 회복.)

## 스펙 #5 — 캐시 자료구조

**조직별 단일 HASH** — `latest:{orgId}`, field=deviceId, value=렌더셋(#4) JSON.

| 연산 | 명령 |
|---|---|
| 조직 스냅샷(접속 시, 지배적 읽기) | `HGETALL latest:{orgId}` — 1 round-trip |
| write-through | `HSET latest:{orgId} {deviceId} {json}` |
| 조직 이동 | `HDEL latest:{old} {id}` + `HSET latest:{new} {id}` |
| 단건 조회 | `HGET latest:{orgId} {deviceId}` |

**근거:** 지배적 읽기(조직 스냅샷)를 보조 색인 없이 HGETALL 1회로 처리한다. 대안 key-per-device는 스냅샷용 조직→device 색인을 따로 유지해야 하고, 그 유일한 이점(디바이스별 TTL)은 스펙 #3(오프라인 미만료)이 무효화한다. 전역 HASH + 읽기 필터는 스냅샷이 O(fleet)가 돼 파티션 이득을 잃는다.

**뷰포트/GEO는 부하 측정 게이트로 뒤로:** "뷰포트 렌더링"(클라이언트가 화면 안만 그림)은 항상 하고 GEO가 필요 없다. "서버측 뷰포트 필터링"(GEO)은 한 조직이 통째 전송이 부담될 만큼 클 때만 필요하다. GEO는 HASH를 대체하지 않고 얹는다(공간 색인 `geo:{orgId}` + 속성 HASH, `GEOSEARCH`→`HMGET`) — HASH-first는 버려지지 않으므로 나중 추가가 싸다. 지금은 클라측 뷰포트 렌더로 충분하고, 큰 조직 HGETALL 응답 크기가 측정에서 문제로 드러나면 HSCAN/GEO로 escalate.

## 스펙 #6 — M4a 측정 설계 (읽기경로 헤드라인)

**결정 근거 vs 측정 구분.** Redis 도입 결정은 push(스펙 #1)가 이미 정당화한다 — push는 접속마다 조직 전체 스냅샷을 O(1)로 줘야 하고, 매 접속 DB 조회로는 규모에서 안 버틴다(아키텍처 필수 부품이지 추측성 최적화가 아님). 따라서 측정은 **결정을 정당화하려는 게 아니라 읽기경로 개선 효과를 기록**하는 것이다. → **최적 DB 쿼리(DISTINCT ON 등) baseline은 두지 않는다** — 결정을 measurement로 거는 게 아니라 strawman 방어가 불필요.

**단순 before/after** (효과 기록, 쓰기경로 "랜덤→순차"의 읽기 버전):
- **Before**: `GET /api/telemetry/latest` = `findLatestPerDevice()` 상관 서브쿼리(TimescaleDB).
- **After**: 같은 엔드포인트 = `HGETALL latest:{orgId}`(Redis).
- **독립변수 = 디바이스 수** 1k → 5k → 10k. before는 degrade, after는 평평.
- **데이터셋 = 디바이스당 ~1,000행**(통제 적재; N 정확값 비민감, 빈 테이블만 아니면 됨). 캐시 비용은 이력 깊이와 무관(최신 1건).
- **지표**: 엔드포인트 p50/p95/p99(디바이스 수별) · after HGETALL 지연·응답 크기(스펙 #5 escalate 판단).
- **부작용**: write-through `HSET`가 적재 throughput을 깎나 — on/off 비교("읽기 개선을 쓰기 희생 없이").
- **격리**: (a) 읽기 단독 헤드라인 먼저 → (b) 적재 동시는 여유 시. 웜 캐시 헤드라인 + 콜드 미스 fallback 1회 기록.

---

## 아직 안 정한 것 (다음 스펙 항목)
- **push 프로토콜 모양**(M4b): 접속 시 조직 스냅샷 → 이후 델타. 델타 포맷, 재접속·초기동기화.
- **파생 임계값 수치**(타입별 가능): 오프라인(lastSeen 나이) · 저전력 밴드 경계 · **캐시 TTL 값**.
- **서버측 뷰포트 필터(GEO)**: 부하 측정 게이트 — 큰 조직 HGETALL이 문제로 드러나면 도입(스펙 #5).

## 관련 결정 갱신
- [ROADMAP](../ROADMAP.md) 보류표: "디바이스 그루핑/스코핑"을 조직 1:N(`orgId`)로 구체화, 관리자↔조직 M:N·권한 강제는 인증 유지.
- [STATUS](../STATUS.md) 결정 로그: M4 읽기경로 스펙 3건 기록.
- [ADR 0007](../decisions/0007-messaging-storage-redis-streams-and-governance.md): M4 영향 항목에 이 스펙 포인터.
