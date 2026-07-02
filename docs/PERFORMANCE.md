# 성능 병목 추적 — 측정으로 병목을 옮겨온 기록

실시간 디바이스 텔레메트리 시계열 파이프라인의 성능을 마일스톤별로 개선한 기록이다.
일하는 방식은 하나다: **병목을 측정으로 특정 → 해결 → 병목이 어디로 이동했는지 재측정.**
추측("느려질 것 같다")이 아니라 결정적 지표(deciding metric)로 병목을 인과 증명하고, 해결 뒤에는 반드시 다음 병목을 다시 측정한다.

모든 수치는 단일 측정 박스에서 나왔다: i7-6700HQ 4c/8t · 8GB · **5400rpm HDD**(단일 회전 디스크).
절대값은 이 디스크에 종속되므로, 아래 수치는 절대 성능이 아니라 **구조·쿼리 간 비율**로 읽는다.
상세 수치·해석·원본 데이터는 각 절 끝의 `measurements/Mx.md`에 있다.

---

## 병목 이동 지도

| 단계 | 병목 | 핵심 증거 | 해결 | 결과 | 이동한 다음 병목 |
|---|---|---|---|---|---|
| **① M0** 수집 baseline | HDD `fsync` | CPU 유휴 2~8% · 디스크 `%util` 97% · `f_await` 25ms · 계산 일치(40 fsync/s ÷ 1.2 fsync/req ≈ 33) | 단건 insert + 커밋마다 `fsync`(naive) | **33 req/s** | `fsync` 횟수 자체 |
| **② M1** 배치 적재 | `fsync` 횟수 | fsync/req 1.2 → 0.059 | 인메모리 큐 + 배치 워커 | **1,437 req/s (~44×)** | InnoDB B-tree 랜덤 위치 쓰기(data `fsync`) |
| **③ M2** 순차 저장 | 랜덤 위치 쓰기 | 디스크 평균 쓰기 200KB(순차) · 피크 59%(미포화) | TimescaleDB 하이퍼테이블(순차 append) | **5,459 rows/s (~3.8×)** | 단일 배치 워커(HikariCP active=1) |
| **④ M2-par** 워커 병렬화 | 단일 워커 + device 행 락 | N=6→12→16 처리량 스케일 · 데드락 재현 | 워커 N=16 + `TreeMap` 락 순서 + 큐 200k | **도착 10k rows/s 무손실** | 시간에 따른 DB 성장 |
| **⑤ M2-sustain** 지속 적재 | DB 성장(현재 청크 인덱스 > `shared_buffers`) | 디스크 읽기 ≈ 0(직접 증거) · 63분 편차 ±1% | 청크 7일 → **5분** + retention 12h | **지속 10k 평평, 드롭 0** | (읽기경로로 전환) |
| **⑥ M4a** 읽기경로 | 쿼리 복잡도(일 = O(전체 행)) | `EXPLAIN` loops=1M · external merge spill | 상관 서브쿼리 → **`LATERAL`**(O(디바이스)) | 8.65s → **35ms (~250×)**, 캐시 없이 | 응답 페이로드 425KB |
| **⑦ M4b** 실시간 | 적재 핫패스에 push가 얹힘 | A 데모에서 커넥션 풀 고갈 | Redis Streams fan-out(`storage`/`monitoring` 컨슈머 그룹) + WebSocket push | **지속 10k 무손실(워커4)·재시작 at-least-once** | Redis 버퍼(MAXLEN vs maxmemory) → storage 워커 → 디스크 |

한 줄로: **33(fsync) → 44× 배치 → 3.8× 순차 저장 → 10k 무손실 → 읽기 250× → 실시간 fan-out(지속 10k 무손실·재시작 at-least-once).**
각 단계의 해결은 병목을 없앤 게 아니라 **다음 층으로 이동**시켰고, 그 이동을 다시 측정한 것이 이 문서의 골격이다.

---

## 방법: 결정적 지표로 인과를 증명한다

각 단계는 "무엇이 병목인가"를 가리키는 **하나의 결정적 지표**를 먼저 정하고, 그 지표가 움직일 때 최대 처리량(throughput)이 따라 움직이는지로 인과를 증명한다.
예를 들어 M1의 결정적 지표는 **요청당 `fsync` 횟수**(`Innodb_data_fsyncs` 델타 ÷ 요청 수)다.
이 값이 1.2에서 0.059로 줄자 최대 처리량이 44배 올랐다 — "병목 = fsync"가 상관이 아니라 인과임을 이 한 쌍의 움직임이 보인다.
지표를 정하지 않고 처리량만 보면 "빨라졌다"까지만 말할 수 있고, 왜 빨라졌는지는 증명하지 못한다.

---

## 단계별 기록

각 단계는 같은 틀로 정리한다: **상황**(무엇을 하고 있었나) · **병목**(무엇이 한계였나) · **원인**(측정으로 어떻게 규명했나) · **해결**(무엇을 바꿨나) · **결과**(수치) · **다음 병목**(어디로 이동했나).

### ① M0 — 수집 baseline

- **상황** — 가장 단순한 구현. 요청 한 건마다 행 하나를 insert하고 트랜잭션을 커밋한다.
- **병목** — 커밋마다 `fsync`가 걸린다. 5400rpm 단일 HDD에서 이 동기 쓰기가 최대 처리량을 **33 req/s**로 묶었다.
- **원인** — CPU 유휴 2~8%(계산이 병목 아님), HikariCP 커넥션 풀 대기, 디스크 `%util` 97%·`f_await` 25ms. 계산으로도 설명된다: 디스크 40 fsync/s ÷ 요청당 1.2 fsync ≈ 33.
- **결과** — baseline **33 req/s**(이후 모든 비교의 기준점).
- **다음 병목** — `fsync` 횟수 자체. 커밋을 모으면 요청당 `fsync`를 줄일 수 있다 → ②.

상세: [measurements/M0.md](measurements/M0.md)

### ② M1 — 배치 적재

- **상황** — baseline 33 req/s. 병목은 요청당 `fsync` 횟수.
- **병목** — 요청 1건마다 커밋 1회 → `fsync` 1회. 요청당 `fsync` 1.2회.
- **해결** — 수집 요청을 인메모리 큐에 넣고 배치 워커가 모아서 한 번에 insert·커밋한다(`JdbcTemplate.batchUpdate`). 요청 N건이 `fsync` 1회를 공유. 내구성 유지(`flush=1`).
- **결과** — fsync/req 1.2 → 0.059, 최대 처리량 **33 → 1,437 req/s(약 44배)**.
- **다음 병목** — 로그 `fsync`가 아닌 **data `fsync`**. InnoDB가 B-tree 인덱스 여러 페이지를 랜덤 위치에 갱신하는 쓰기가 남는다.

상세: [measurements/M1.md](measurements/M1.md)

### ③ M2 — 순차 저장 (TimescaleDB)

- **상황** — 배치로 커밋을 모았는데도 디스크가 다시 병목. 이번엔 랜덤 위치 쓰기.
- **병목** — InnoDB B-tree는 데이터를 여러 페이지의 랜덤 위치에 갱신한다. 회전 디스크에서 랜덤 위치 쓰기는 순차 쓰기보다 느리다.
- **해결** — 앱 전체를 MySQL에서 PostgreSQL/TimescaleDB로 교체하고, 텔레메트리를 하이퍼테이블에 시간순으로 append(순차 쓰기).
- **결과** — 최대 처리량 **1,437 → 5,459 rows/s(약 3.8배)**. 디스크 평균 쓰기 200KB(순차)·피크 59%로 포화에서 벗어남 = 랜덤→순차 전환의 직접 증거.
- **다음 병목** — 애플리케이션. 배치 워커가 하나뿐이라(HikariCP active=1) 여유가 생긴 디스크 대역폭을 다 쓰지 못한다.

상세: [measurements/M2.md](measurements/M2.md)

### ④ M2-par — 워커 병렬화

- **상황** — 디스크는 놀고 있고 워커가 하나. 도착 목표는 10k rows/s.
- **병목 1** — 단일 워커가 디스크 순차 대역폭을 못 채운다.
- **해결 1** — 배치 워커를 1개 → N개로. 공유 큐를 나눠 drain, 워커당 커넥션 1개. 여러 커밋이 group commit으로 묶여 스케일한다(N=6 9.5k → N=12 12.8k → N=16 14.4k, 대역폭에 수렴).
- **병목 2** — 매 요청이 device 행을 upsert하는데, 여러 워커가 같은 device 행 락을 다툰다. device 갱신 off면 N=12에서 12.8k(디스크 100%), on이면 9k(디스크 80%). 게다가 도착 순서대로 락을 잡다 `deadlock detected`.
- **해결 2** — device_id를 정렬해(`TreeMap`) 모든 워커가 같은 순서로 락을 잡게 함(데드락 제거). 큐를 200k로 잡아 체크포인트가 drain을 멈출 때의 backlog(약 12k)를 흡수.
- **결과** — append-only N=16 + 큐 200k로 **도착 10k rows/s에서 드롭 0**, 내구성 유지, 단일 5400rpm HDD.
- **다음 병목** — 시간에 따른 DB 성장. 그리고 device 갱신이 적재를 막는다는 이 측정이 device 상태를 적재 경로에서 분리하는 M4의 근거가 됐다.

상세: [measurements/M2-par.md](measurements/M2-par.md)

### ⑤ M2-sustain — 지속 적재

- **상황** — M2-par는 매 런마다 테이블을 비워(`TRUNCATE`) 순간 용량을 쟀다. 실제 운영은 시간이 지나며 DB가 커진다.
- **병목** — 통제 없이 오래 적재하면 처리량이 13k에서 10k 밑으로 떨어진다. 원인은 DB 성장이다: 기본 청크가 7일이라 10k/s에서 현재 청크가 수십억 행으로 커지고, 그 청크의 인덱스가 `shared_buffers`(2GB)를 넘으면 insert마다 인덱스 페이지를 디스크에서 읽어야 해 drain이 도착률 밑으로 떨어진다.
- **해결** — 청크를 7일 → **5분**으로 줄여 현재 청크(약 300만 행)의 인덱스를 `shared_buffers` 안에 유지. 디스크 총량은 retention 12h로 묶음(약 133GiB, 디스크 38%).
- **결과** — 63분간 도착 10k가 평평(편차 ±1%, 드롭 0). 순수 insert 워크로드의 디스크 읽기가 거의 0 = 인덱스가 캐시에 있다는 직접 증거.
- **폐기한 시도** — 백그라운드 압축. 결과물은 7,177MB → 177MB(40.56배)로 좋지만, 과정의 I/O가 문제였다. 1시간 지난 청크는 캐시에 없어 압축이 디스크를 읽어야 하고(0 → 11 MB/s), 쓰기만으로 이미 88%인 단일 HDD가 95%+로 포화하며 flush가 105ms → 700ms로 늘어 27분간 5,858,290행을 드롭했다. M2-par의 bgwriter와 같은 결과 — 포화한 단일 HDD에 경쟁 I/O를 더하면 drain의 I/O를 빼앗는다. 결론: 단일 HDD에선 지속 무손실 10k와 백그라운드 압축이 양립 불가 → raw 적재 + retention drop(압축은 전용 디스크 생기면 재도입).

상세: [measurements/M2-sustain.md](measurements/M2-sustain.md)

### ⑥ M4a — 읽기경로 쿼리

- **상황** — 여기서 병목은 디스크가 아니라 쿼리 방식. 관제 지도의 "디바이스별 최신 1건"(`GET /api/telemetry/latest`)을 M0은 상관 서브쿼리로 짰다.
- **병목** — 서브쿼리가 행마다 재실행된다(`EXPLAIN` loops=1,000,000). 결과는 디바이스 수(1,000)뿐인데 일은 전체 행 수(100만)에 비례 → 100만 행에서 단일 조회 8.7s.
- **해결** — 같은 조회를 세 방식으로 짜 비교하고 `LATERAL`을 택했다.

  | 쿼리 | 접근 | @1M행 | @10M행 |
  |---|---|---|---|
  | ① 상관 서브쿼리 | 행마다 "이 디바이스의 최신인가" 재확인 | 8,748 ms | — |
  | ② `DISTINCT ON` | 전체를 정렬해 device별 첫 행 | 813 ms | 24분 |
  | ③ `LATERAL` | device 목록으로 시작, device당 PK 인덱스 1회 | — | 웜 406 ms |

  ②는 최신만 필요해도 전체 행을 정렬해, `shared_buffers`를 넘으면(10M ≈ 3GB) 디스크 정렬(external merge)로 24분까지 급락한다. ③은 `device.org_id`로 device를 좁힌 뒤 device당 PK 인덱스(`device_id, recorded_at`)로 최신 1행만 집어 일을 결과 크기 O(디바이스)에 맞춘다.
- **결과** — 엔드포인트 지연 상관 서브쿼리 대비 **약 250배**(k6 VUS=1 p95 8.65s → 35ms), 캐시 없이 쿼리 개선만으로.
- **다음 병목** — 쿼리가 아니라 응답 크기. 조회 응답이 약 425KB(디바이스 1,000 × 풀 텔레메트리)라 지도가 그리는 필드만 담는 경량 렌더셋이 다음 레버. (Redis 캐시 `LatestStateLookup` 포트는 코드는 있으나 쿼리 ③으로 충분해 기본값 DB로 껐다 — 캐시의 값어치는 M4b push의 접속 시 스냅샷 소스에서.)

상세: [measurements/M4a.md](measurements/M4a.md)

### ⑦ M4b — 실시간 fan-out (Redis Streams)

- **상황** — 읽기경로 마지막 조각. 관제 지도를 폴링에서 push로 전환.
- **병목** — 초기 데모(A)에서 적재 핫패스에 WebSocket push를 직접 얹었더니 커넥션 풀이 고갈됐다. 적재와 push가 같은 경로를 다툰다.
- **해결** — 인메모리 큐를 Redis Streams로 바꾸고 독립 컨슈머 그룹 둘로 분리. `storage` 그룹은 배치 적재(적재 후 `XACK`, `ON CONFLICT`로 멱등), `monitoring` 그룹은 최신부터 읽어 WebSocket push. 각 그룹이 커서를 따로 가져 push가 적재를 막지 않는다.
- **결과** — 박스 측정에서 **병목이 세 번 이동**했다. (1) **Redis 버퍼**: `MAXLEN` 기본 1M이 `maxmemory` 256mb보다 커(1M×0.5KB≈512MB) 트리밍이 못 걸려 OOM — 스트림은 append-only 로그라 `XACK`해도 엔트리가 안 지워진다. `MAXLEN`을 메모리 예산에서 역산해 bound. (2) **storage 워커**: 1→2→4로 늘리자 유실 34.5→13.7→0%, 디스크 %util 37→90→95%로 병목이 애플리케이션에서 디스크로 이동. (3) **디스크**: 워커 4로 **지속 10k 무손실**(디스크 60% 여유). 초과 부하는 유실이 아니라 admission 백프레셔(intake 지연↑ + 생성기 drop). **재시작**은 부하 중 `kill -9` 후 `DB=202성공수`·`pending→0`으로 **무손실·무중복**(at-least-once + 멱등) 실증. 결정적 세부: **버퍼는 worst-case 컨슈머 랙보다 커야**(트림은 oldest부터) — `MAXLEN 200K`는 warm-up lag 스파이크에 0.037% 유실, 400K는 0%.
- **측정 위생 교훈** — 짧은 부하 런은 warm-up 트랜지언트를 정상상태로 오인한다. 90s 런은 10k에서 6k만 delivered했으나, 5분 런은 정상상태에서 flat 10k를 유지했다.

상세: [measurements/M4b.md](measurements/M4b.md) · 원본 [M4b-raw/](measurements/M4b-raw/)

---

## 관통하는 원칙

- **병목은 사라지지 않고 이동한다.** 각 단계의 해결은 병목을 다음 층으로 옮겼다(디스크 fsync → fsync 횟수 → 랜덤 위치 쓰기 → 단일 워커 → device 락 → DB 성장 → 쿼리 복잡도 → 응답 크기). 계단식 이동을 매번 재측정하는 것이 이 프로젝트의 방식이다.
- **결정적 지표로 인과를 증명한다.** 처리량만 보면 "빨라졌다"까지고, 지표(fsync/req, 디스크 읽기량, `EXPLAIN` loops, `accepted−DB` 유실, `XLEN` vs `MAXLEN`)가 함께 움직여야 왜 빨라졌는지가 증명된다. 오진도 정직히 기록했다 — M2의 k6 타임스탬프 미래 표류를 서버 붕괴로 오진한 일, M2-par에서 램프 피크를 SLO 달성으로 조기 선언한 일, M4b에서 90s 런의 warm-up 트랜지언트(6k)를 지속 천장으로 오인했다가 5분 런(정상상태 10k)으로 바로잡은 일.
- **폐기한 시도도 근거로 남긴다.** 단일 HDD에 경쟁 I/O를 더하는 시도(M2-par bgwriter 트리클, M2-sustain 백그라운드 압축)는 같은 이유로 무손실 적재를 깼다. 효과 없던 시도를 지우지 않고 남겨야 다음 판단의 근거가 된다.

---

## 상세 문서

| 단계 | 문서 |
|---|---|
| ① 수집 baseline | [measurements/M0.md](measurements/M0.md) |
| ② 배치 적재 | [measurements/M1.md](measurements/M1.md) |
| ③ 순차 저장 | [measurements/M2.md](measurements/M2.md) |
| ④ 워커 병렬화 | [measurements/M2-par.md](measurements/M2-par.md) |
| ⑤ 지속 적재 | [measurements/M2-sustain.md](measurements/M2-sustain.md) |
| ⑥ 읽기경로 쿼리 | [measurements/M4a.md](measurements/M4a.md) |
| ⑦ 실시간 fan-out | [measurements/M4b.md](measurements/M4b.md) · 원본 [M4b-raw/](measurements/M4b-raw/) |
| 진행 현황 | [STATUS.md](STATUS.md) · [ROADMAP.md](ROADMAP.md) |
