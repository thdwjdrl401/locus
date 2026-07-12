# M4b — Redis Streams fan-out 적재 (수집경로 실시간화)

> 상태: **측정 완료(2026-07-02)**. 트리거: M4 실시간([스펙](../specs/M4-realtime-read-path.md)). 인메모리 큐(M2-par)를 Redis Streams로 바꿔 적재와 실시간 push를 독립 컨슈머 그룹으로 분리([ADR 0007](../decisions/0007-messaging-storage-redis-streams-and-governance.md)).
> 측정: 박스(SUT)에서 `mode=stream` 실 기동 + 맥 k6(`load/telemetry-capacity.js`, 열린 모델). 실 Redis·실 kill. 절대수치는 이 HDD 박스 종속 → 구조·설정 간 비율로 해석. 대시보드 `locus-m4b-stream`.

## 목표
1. **적재 처리량**: fan-out을 Streams로 바꿔도 목표 SLO(업링크 10k)를 무손실로 유지하는가.
2. **재시작 무손실·무중복**: 부하 중 앱이 죽었다 살아나도 데이터를 잃지도 중복하지도 않는가(크래시 복구 = at-least-once + 멱등).

## 구성
- `mode=stream`: 수집 `POST /api/telemetry` → 검증 → `XADD telemetry.stream`(즉시 202). 두 컨슈머 그룹이 독립 커서로 각자 전량 소비 — `storage`(배치 적재, 적재 후 `XACK`, `ON CONFLICT (device_id, recorded_at) DO NOTHING`으로 멱등) · `monitoring`(WebSocket push, best-effort).
- `XADD ... MAXLEN ~N`(근사 절단)로 스트림 길이를 bound. 보존·리플레이는 TimescaleDB.
- 제어 변수: `INGEST_WORKERS`(storage 워커 수), `--locus.ingest.stream-maxlen`(스트림 상한).

---

## 한계 1 — MAXLEN 1,000,000이 Redis maxmemory(256mb)에서 OOM

첫 stream 부하(워커 1, `MAXLEN` 기본 1,000,000)에서 도착률 ~6K/s부터 **요청의 35%가 HTTP 500**으로 실패했다. 원인은 Redis OOM:

```
io.lettuce.core.RedisCommandExecutionException:
    OOM command not allowed when used memory > 'maxmemory'.
```
- `used_memory 256.09M == maxmemory 256.00M`(꽉 참), `total_error_replies 313,890 ≈ 실패 요청 수`.
- 스트림 엔트리당 실측 `296,426,393 B / 580,285 ≈ 511 B`. 산수로도 `MAXLEN 1M × 511B ≈ 512MB > 256MB` → **1M은 이 Redis에서 도달 불가 = 트리밍이 한 번도 안 걸림.**

**근본 원인은 Redis Streams의 의미론이다: 스트림은 append-only 로그이며 `XACK`는 삭제가 아니다.** 소비/`XACK`는 그 컨슈머의 PEL(pending entries list)만 비우고, 엔트리 자체는 `MAXLEN`/`XTRIM`/`XDEL`로만 로그에서 제거된다. 스트림 하나에 컨슈머 그룹이 여럿(`storage`·`monitoring`)이고 각자 속도가 달라, Redis는 "모두 읽었는지" 모르니 보존은 프로듀서가 `MAXLEN`으로 명시하는 책임이다. `storage`가 다 소비해도(lag 0) `MAXLEN`이 안 걸리면 스트림은 무한 append → 256mb OOM.

### 변경 — MAXLEN을 메모리 예산에서 역산해 bound
`MAXLEN = 메모리예산 ÷ 엔트리크기`. 511B/엔트리 기준 100MB ≈ 200K, 200MB ≈ 400K. 단 **`MAXLEN`은 worst-case 컨슈머 랙보다 커야** 미소비분이 안 잘린다(트림은 oldest부터 = 유실). `storage`(내구 경로)는 절대 잘리면 안 되고, `monitoring`(best-effort)은 잘려도 재접속 스냅샷이 치유.

### After — OOM 제거
`MAXLEN 200,000`으로 재측정: **HTTP 실패 0%, intake p95 16ms**(OOM 때 869ms/35% 실패). XLEN이 200K 상한에 붙어 메모리 평평, 500 0건. Redis 병목 제거.

---

## 한계 2 — 병목이 storage 워커 → 디스크로 이동 (워커 스윕)

`MAXLEN 200,000` 고정, 도착 램프 2K→12K(단계별 30s), storage 워커 수만 스윕. 인테이크(202)는 모두 통과했으나 **적재가 도착을 못 따라가면 버퍼가 200K에 차고 트림이 미소비 엔트리를 버린다**(유실). 유실 오라클 = `202 accepted − DB count`(충돌은 12K 디바이스 풀에서 ~0이라 dedup 아님).

| 워커 | 저장(DB) | 유실 | 유실% | insert≈/s | 디스크 %util 최고 | 병목 |
|---|---|---|---|---|---|---|
| 1 | 1,100,137 | 579,003 | 34.5% | ~4.4K (포화) | 37% | 단일 배치 직렬화 |
| 2 | 1,448,413 | 229,896 | 13.7% | ~5.8K (포화) | 90% | 디스크 근접 |
| 4 | 1,677,821 | **0** | **0%** | ~6.7K (램프 평균) | ~95% | 디스크 |

(accepted ≈ 1.68M 동일. insert는 DB÷250s. 워커 1·2는 유실 상태라 이 값이 적재 최대 처리량이지만, 워커 4는 램프를 무손실로 따라잡아 이 값이 도착 평균일 뿐 최대 처리량이 아니다 — 지속 최대 처리량은 flat 10K 지속 런에서 확인.)

- 워커는 효과 있지만 **sublinear**(×2 워커 → insert ×1.32). 1워커의 디스크 저활용(37%)은 단일 배치 직렬화 탓 — 병렬화가 디스크를 채운다.
- 4워커에서 **디스크 ~95%로 포화 근접**, 램프 전체를 무손실 흡수. **병목이 애플리케이션(storage 직렬화)에서 디스크로 이동.** 단일 5400rpm HDD의 체감 수확 감소(M2-par와 동일).

---

## 한계 3 — 짧은 부하는 warm-up을 측정한다 (지속 10K)

`MAXLEN 200,000`·워커 4·**flat 10K**:

- **90s 런**: delivered가 6K에 그침, `dropped_iterations 330,505`, intake p95 610ms. "10K 못 버팀"으로 보인다.
- **5분 런**: 정상상태에서 **delivered flat 10K 유지**(202 패널), intake avg ~10ms, **디스크 65%**(포화 아님). 90s 런은 warm-up 트랜지언트(JIT·PostgreSQL 캐시·배치 파이프라인 프라이밍, ~60s)를 못 벗어나고 끝난 것이었다.

**교훈: 짧은 부하테스트는 warm-up을 정상상태로 오인한다.** 5분 런이 진짜 정상상태를 드러냈다 — stream 모드는 **정상상태 10K/s를 디스크 여유로 지속**한다.

### 한계 4 — 버퍼가 peak lag보다 커야 진짜 무손실
5분 flat 10K(`MAXLEN 200K`)에서 **1,042건(0.037%) 유실**. 트림 유실 조건은 `lag > MAXLEN`인데, **warm-up 때 storage 드레인이 잠깐 느려 lag이 순간 200K를 넘겨** 그만큼 잘렸다. 정상상태(warm 이후)는 lag 0 = 무손실.

`MAXLEN 400,000`(≈200MB < 256mb)으로 재현: **DB = accepted = 2,780,515, 유실 0.** warm-up lag 스파이크(~200K)가 400K 밑이라 트림이 미소비분을 안 건드림. → **"버퍼는 peak consumer lag(warm-up 포함)보다 커야 한다"**가 수치로 실증(200K=0.037% → 400K=0%).

---

## goal 2 결과 — 지속 10K 무손실

| 지표 | 값 (워커 4, `MAXLEN 400K`, flat 10K 5분) |
|---|---|
| accepted(202) = DB count | **2,780,515 = 2,780,515 (유실 0)** |
| 정상상태 delivered | **10,000 req/s 평평** |
| intake 지연(정상상태) | avg ~10ms |
| 디스크 %util | ~60% (여유) |
| HTTP 실패 | 0% |

**stream 모드는 업링크 10k SLO를 정상상태에서 무손실로 충족한다.** 초과 부하(예: cold start의 순간 10K 요구)는 **admission으로 흘린다** — intake 지연↑ + k6 생성기 drop이지 파이프라인 유실이 아니다(우아한 백프레셔). 데이터를 받았으면(202) 반드시 저장한다.

---

## goal 1 결과 — 재시작 무손실·무중복

워커 4·`MAXLEN 400K`·flat 6K(용량 안쪽, storage가 따라잡아 pending 회수만 변수), 부하 ~40s에 `kill -9` → 즉시 재기동(다운타임 11s).

| 지표 | 값 |
|---|---|
| k6 202 성공 | 527,928 |
| k6 실패(다운타임 EOF·연결거부) | 65,047 (10.96%) |
| **DB count** | **527,928** |
| storage pending(드레인 후) | **0** |

- **DB = 202 성공수 정확히 일치 → 무손실.** DB가 202수를 초과 안 함 → **무중복**(재기동 시 storage가 자기 pending을 offset `"0"`부터 재처리, `ON CONFLICT`가 재처리분 흡수).
- 다운타임 중 실패한 65K는 애초에 accepted가 아니니 DB 기대치가 아니다(정합).

**크래시 복구(at-least-once + 멱등 재처리)를 실 Redis·실 kill로 검증.**

---

## 해석 — 병목 이동과 트레이드오프

- **Redis Streams는 큐가 아니라 append-only 로그.** `XACK`는 PEL만 비우고 엔트리는 `MAXLEN`으로만 회수된다. 이 의미론을 놓친 기본값(1M)이 256mb에서 OOM을 냈다. 스트림은 **단기 버퍼**로 설계하고 상한을 메모리 예산에서 역산해야 한다(ADR 0007).
- **병목 이동**: Redis 버퍼(OOM) → storage 워커(직렬화) → 디스크(단일 HDD). 정상상태 10K에선 셋 다 여유이고, 그 위는 intake 경합·admission이 앞선다.
- **폭주 시맨틱은 트레이드오프 삼각형이다** — 메모리 바운드 ↔ 무손실 ↔ 백프레셔 중 둘만 강하게 가진다.
  - blind `MAXLEN`(현재): 메모리 바운드 + (충분히 크면)무손실. 단 지속 과부하로 `lag > MAXLEN`이면 미소비분을 조용히 버린다.
  - 완전 무손실을 원하면 `XTRIM MINID`(모든 내구 그룹이 소비한 ID 아래만 절단) — 대신 컨슈머 정체 시 메모리 무한.
  - 내구 경로(`storage`)는 유실 없게 사이징, 실시간 경로(`monitoring`)는 버려도 됨(스냅샷 치유) — 2-그룹 분리가 이 갈래를 이미 구현.
- **vs 인메모리 큐(M2-par)**: stream은 XADD/XREADGROUP/XACK 왕복 오버헤드가 있다. 대신 **내구 버퍼 + fan-out(push를 적재 핫패스에서 분리) + 크래시 복구**를 얻는다. 정당한 트레이드오프 — push가 적재를 막던 초기 데모(A)의 커넥션 풀 고갈이 근본 해소됐다. (stream의 최대 처리량은 SLO 10K 달성에서 멈춰 더 밀지 않음.)
- **warm-up 구간을 잘못 해석한 기록**: 90s 런의 "10K 못 버팀"은 정상상태가 아니라 트랜지언트였다. 지속 런으로 바로잡았다.

## 남은 것 / 한계
- **기본 MAXLEN**: 이 박스(maxmemory 256mb, 10K SLO)에선 400K가 무손실 최소값. `application.yml` 기본값을 400,000으로, `INGEST_STREAM_MAXLEN`으로 override. maxmemory를 바꾸면 MAXLEN도 역산해 조정.
- **intake 경합**: 워커·tomcat·TimescaleDB가 코어 0–5를 공유해 고rate에서 intake p95가 오른다(정상상태는 낮음). 배포 분리(M8) 시 재측정.
- **폭주 정책 확정**: 현재 blind `MAXLEN`(내구 경로는 사이징으로 무손실). `XTRIM MINID` 도입은 메모리 무한 리스크와 함께 별도 결정.
- **monitoring 그룹**: best-effort라 고rate에서 lag이 쌓였다 부하 후 회복. push 지연·유실 허용치는 M4b(A) push 측정에서.

## 후속 (2026-07-09) — 워커 스윕 연장: 8워커로 12K 무손실

M4b 본문은 워커 4에서 10K SLO 무손실을 확인하고 멈췄다(SLO 달성, 더 안 밈). 이후 SLO 위 여유를 밀어봤다: **8코어(`LOCUS_CPU_PINS=0-7`, baseline 0-5 아님)·stream·`device-upsert=true`, k6 인입 ~12K req/s, 워커만 4→8.**

| 워커 | inserted(적재) | disk %util | disk 큐깊이 | CPU | flush 지연 | storage lag | 판정 |
|---|---|---|---|---|---|---|---|
| 4 | ~9.5–10K | ~77% | ~2 | ~78% | ~190ms | — | 인입 12K에 못 따라감(갭 ~2K/s → 스트림 버퍼) |
| **8** | **~11–12K (인입과 매칭)** | ~86% | ~3.5 | 82–98% | ~130–220ms | **0** | **12K 무손실** |

- **워커 4→8이 적재를 10K→12K로.** `XINFO GROUPS`에서 storage(내구) `lag 0·pending 0` = 전량 소비·DB 적재·ACK 완료, 트림 유실 0. (monitoring 그룹만 pending 200 = best-effort in-flight, 정상.)
- **개별 flush는 여전히 ~180ms**(HDD fsync 그대로)지만 8워커 병렬로 aggregate가 오름. 병렬화가 디스크 빈 사이클을 채움(util 77→86%, 큐 2→3.5) — 본문 "한계 2"의 sublinear 워커 효과 연장.
- 12K에서 **CPU 82–98% + 디스크 큐 3.5** 동시 근접 = 다음 병목은 코어(CPU)+디스크 flush 지연(레버=SSD). 하드웨어 블록.

**측정 중 오진 정정(2026-07-09, 측정 위생 기록):** 진단 중 "10K가 디스크 대역폭 한계"라 했으나 틀렸다. disk %util 77%(포화 아님)에서 막힌 건 **워커4의 병렬화 부족**이었고 8워커로 12K까지 올랐다. **`%util`로 saturation을 판단한 게 오류** — 실제 신호는 flush 지연(20→190ms)·큐깊이(0→3.5)였다. (M4b 본문의 "workers4=10K SLO 무손실"은 유효; 이건 SLO 위 특성화.)

주의(비교 범위): 이 12K는 8코어(0-7)·fleet 부하(`telemetry-fleet.js`, VU=디바이스)라 본문 10K(workers4·capacity 모델·코어0-5)와 apples-to-apples 아님. 깨끗한 단일변수 비교는 **세션 내 동일 8코어에서 워커 4→8(10K→12K)**.

## 측정 환경·지표
| 항목 | 값 |
|---|---|
| SUT(박스) | i7-6700HQ 4c/8t · 8GB · 5400rpm HDD · Locus는 코어 0–5·힙 1.5G로 격리 |
| DB | TimescaleDB 2.17.2-pg16 · shared_buffers 2GB · 청크 5분 |
| Redis | 7.4-alpine · maxmemory 256mb · noeviction · 영속화 끔(`--save "" --appendonly no`) |
| JVM | JDK 21 · `-Xms/-Xmx 1500m` · G1GC |
| 앱 | `INGEST_MODE=stream` · `INGEST_WORKERS` 1/2/4 · `stream-maxlen` 200K/400K |
| 부하 | 맥 k6 `telemetry-capacity.js`(열린 모델, 도착률) · 디바이스 풀 12,000 · 유선 LAN |
| 대시보드 | `locus-m4b-stream` |
