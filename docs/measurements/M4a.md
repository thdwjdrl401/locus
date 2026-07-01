# M4a — 최신상태 조회: 상관 서브쿼리 → DISTINCT ON → 캐시 (읽기경로)

> 상태: **진행 중(2026-07-01~)** — before(naive)·쿼리 수정(DISTINCT ON) 측정 완료, **캐시(after) 예정**. 트리거: M4 실시간 읽기경로([스펙](../specs/M4-realtime-read-path.md)). 관제 지도의 "디바이스별 최신"(`GET /api/telemetry/latest`)을 스케일에서 검증한다.
> 측정: 정적 데이터셋(N 디바이스 × 1,000행 벌크 시드, `load/seed-latest-dataset.sql`)에 `EXPLAIN (ANALYZE, BUFFERS)` + k6 읽기(`load/telemetry-latest-read.js`, 닫힌 모델). 절대수치는 이 HDD 박스 종속 → **스케일 간 비율·추세로 해석**. 원본 EXPLAIN·k6는 [M4a-raw/](M4a-raw/).

## 목표
디바이스별 최신 1프레임 조회를 **디바이스·이력 규모가 커져도 실시간**으로 유지한다. SLO: 조회 1만 대 실시간([ROADMAP](../ROADMAP.md)).

## 부하로 드러난 한계 (naive 상관 서브쿼리)
M0 기준 구현은 상관 서브쿼리다:
`... WHERE t.recorded_at = (SELECT MAX(t2.recorded_at) FROM telemetry t2 WHERE t2.device_id = t.device_id)`.
1k 디바이스 × 1,000행(100만 행)에서 **단일 조회 8.7s**. `EXPLAIN`상 서브쿼리가 **행마다 재실행**(`loops=1000000`) = **O(N²)** 병리 플랜. Buffers 전부 `shared hit`(디스크 읽기 0) → 데이터는 메모리에 있고 **CPU가 병목**(나쁜 플랜이 100만 번 계산).

## Before — naive (1M행)
| 지표 | 값 |
|---|---|
| EXPLAIN Execution | **8,748 ms** |
| 플랜 | Append(청크별 Seq Scan) · SubPlan `loops=1,000,000` |
| Buffers | shared hit 3,041,668 (read 0 = 디스크 안 읽음) |
| k6 VUS=1 | p95 **8.65s** · med 8.47s (9 req) |
| k6 VUS=20 | p95 **47.6s** · avg 35.2s · HikariCP active 16 포화 · pending 4 |

지도는 이 엔드포인트를 3초 폴링한다. 조회가 8.7s면(1Hz로 ~17분 쌓여 100만 행 도달 시) 새로고침이 폴 주기보다 느려 심하게 지연된다. 트리거는 디바이스 수가 아니라 **누적 총행수**다.

Grafana 캡쳐 대시보드(`locus-m4a-read-summary`):

![VUS=1](img/m4a-read-1k-vus1.png)
![VUS=20](img/m4a-read-1k-vus20.png)

(VUS=20에서 서버측 p95/p99가 30s로 평평한 건 히스토그램 최대 버킷 포화 — 실제 47.6s는 k6.)

## 변경 내용 — DISTINCT ON
`findLatestPerDevice`를 JPQL 상관 서브쿼리 → **네이티브 `SELECT DISTINCT ON (device_id) * FROM telemetry ORDER BY device_id, recorded_at DESC`**. PK 인덱스 `(device_id, recorded_at)`로 device별 최신 1행. (커밋 `cd06ecb`.)

## After — DISTINCT ON (스케일 곡선)
| 총 행수 | EXPLAIN Execution | 플랜 | 비고 |
|---|---|---|---|
| 1M (1k×1k) | **813 ms** | Unique ← Incremental Sort ← Merge Append(청크 인덱스) | shared hit 51,106 |
| 5M (5k×1k) | **6,431 ms** | 동일 | shared hit 5,035,296 · first-touch dirtied 60,560 |
| 10M (10k×1k) | **1,438,707 ms (≈24분)** | Gather Merge ← **Sort(external merge, temp ~3GB 디스크)** ← Parallel Seq Scan(3워커) | shared read 197,256(데이터도 디스크) · temp read/written ~1.17M블록. **플랜이 인덱스 순회 → seq scan+디스크 정렬로 전환** |

naive 대비 **1M에서 8.7s → 0.81s (10.7×)**. 그러나 플랜이 **전체 행을 스캔**(SkipScan 미적용) → **O(전체행)**, 곡선이 두 구간이다:
- **RAM에 담기는 동안(1M·5M)**: 인덱스 순회(Incremental Sort ← Merge Append, device_id presorted), 전부 `shared hit`. 0.81s → 6.4s로 완만히 degrade(5× 데이터에 ~8×, 정렬 + first-touch).
- **RAM 초과(10M ≈ 3GB > shared_buffers 2GB)**: 플래너가 인덱스 경로를 버리고 **Parallel Seq Scan + 전체 Sort**로 전환, 정렬이 work_mem을 넘겨 **디스크로 spill**(external merge, temp ~3GB) → 5400rpm HDD에서 **1,439초(24분)**. 5M→10M에서 **2× 데이터에 225× 지연** — 완만한 degrade가 아니라 RAM 경계에서 급락한다.

## 해석 — 쿼리 수정은 버그픽스지 스케일 해법이 아니다
- naive는 **O(N²)**(서브쿼리가 행마다) = 결함. DISTINCT ON이 이를 **O(전체행)** 으로 고쳐 10.7× 개선 — 소규모에서 지도가 안 깨지게 하는 **버그픽스**.
- 그러나 DISTINCT ON도 **총행수(디바이스 × 이력 깊이)에 degrade**하고, **RAM(shared_buffers 2GB)을 넘으면 플랜 전환 + 디스크 정렬로 급락**한다(10M에서 이미 24분). 운영(retention 12h · 1Hz면 디바이스당 ~43,200행)은 훨씬 큰 규모 → 실시간 불가. (24분은 이 HDD·작은 work_mem이 증폭한 값이나, 매 조회 전체 행을 읽는 O(전체행)은 하드웨어로 안 바뀐다.)
- **최신상태 캐시(Redis)는 O(디바이스)** — 디바이스당 최신 1건만 보유해 이력 깊이와 무관. 캐시는 두 이유로 정당: (1) 읽기 스케일 O(디바이스) vs O(전체행), (2) 실시간 push의 스냅샷 소스([스펙 #1](../specs/M4-realtime-read-path.md)).

## 다음 — 캐시 (after 예정)
조직별 HASH(`latest:{orgId}`) + 배치워커 write-through + `LatestStateLookup` 포트. after = HGETALL 지연이 **행수·이력과 무관하게 평평**함을 실증 + write-through의 적재 부작용(on/off) 측정. docker-compose redis 점증.

## 측정 함정·갭 (정직)
- **서버측 지연 히스토그램 30s 포화**: Micrometer `http.server.requests` 최대 버킷이 ~30s라 VUS=20의 실제 47.6s를 대시보드는 30s로 잘라 표시 → 큰 지연은 **k6(클라이언트)** 신뢰.
- **first-touch dirtying**: 벌크 시드 직후 첫 EXPLAIN은 hint-bit 설정으로 `dirtied` 발생(5M에서 60,560). 웜 재실행이면 소폭 빠름 — 추세 결론 불변.
- **네이티브 쿼리 통합테스트 부재**: DISTINCT ON은 실 DB 통합테스트가 없어(현재 WebMvc 목킹만) 매핑·정확성이 CI로 검증 안 됨. 별도 추가 필요.

## 측정 환경·지표
| 항목 | 값 |
|---|---|
| SUT(박스) | i7-6700HQ 4c/8t · 8GB · 5400rpm HDD |
| DB | TimescaleDB 2.17.2-pg16 · shared_buffers 2GB · 청크 5분(V2) |
| 데이터 | N 디바이스 × 1,000행 벌크 시드(`load/seed-latest-dataset.sql`), 결정적 |
| 부하 | `EXPLAIN (ANALYZE, BUFFERS)` + k6 `telemetry-latest-read.js`(닫힌, VUS=1·20) |
| 대시보드 | `locus-m4a-read`(+ summary). 스크린샷 `img/m4a-read-1k-vus1.png`·`vus20.png` |
| 원본 | [M4a-raw/](M4a-raw/) — EXPLAIN 4건·k6 2건 |
