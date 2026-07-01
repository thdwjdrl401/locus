# M4a 원본 데이터 — 최신조회 읽기경로

`docs/measurements/M4a.md`의 근거 원본. 측정 2026-07-01~02, 박스(i7-6700HQ · 8GB · 5400rpm HDD · TimescaleDB 2.17.2-pg16 · shared_buffers 2GB · 청크 5분).
데이터셋 = `load/seed-latest-dataset.sql`로 N 디바이스 × 1,000행 벌크 시드(결정적). 스케일마다 `TRUNCATE telemetry, device` 후 재시드.

## 파일
- `explain-plans.txt` — `EXPLAIN (ANALYZE, BUFFERS)` 4건: naive(1M) + DISTINCT ON(1M/5M/10M).
- `k6-latest-read.txt` — k6 `telemetry-latest-read.js` 2건(1k 디바이스, VUS=1·20).
- 스크린샷: `../img/m4a-read-1k-vus1.png`(캡쳐 대시보드, VUS=1) · `../img/m4a-read-1k-vus20.png`(VUS=20).

## 요약

| 쿼리 | 총 행수 | EXPLAIN Execution | 비고 |
|---|---|---|---|
| naive 상관 서브쿼리 | 1M | 8,748 ms | SubPlan `loops=1,000,000` (O(N²)), 전부 shared hit |
| DISTINCT ON | 1M | 813 ms | 인덱스 순회(Incremental Sort ← Merge Append), shared hit |
| DISTINCT ON | 5M | 6,431 ms | 동일 플랜, first-touch dirtied 60,560 |
| DISTINCT ON | 10M | 1,438,707 ms (24분) | 플랜 전환: Parallel Seq Scan + Sort(external merge, temp ~3GB 디스크) |

| k6 (naive, 1k, 1M행) | p50 | p95 | 비고 |
|---|---|---|---|
| VUS=1 | 8.47s | 8.65s | 단일 조회 순수 지연 (9 req) |
| VUS=20 | 36.08s | 47.58s | HikariCP active 16 포화·pending 4 (40 req) |

해석·3단 서사(naive→DISTINCT ON→캐시)는 [../M4a.md](../M4a.md).
