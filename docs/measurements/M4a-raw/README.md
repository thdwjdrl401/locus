# M4a 원본 데이터 — 최신조회 읽기경로

`docs/measurements/M4a.md`의 근거 원본. 측정 2026-07-01~02, 박스(i7-6700HQ · 8GB · 5400rpm HDD · TimescaleDB 2.17.2-pg16 · shared_buffers 2GB · 청크 5분).
데이터셋 = `load/seed-latest-dataset.sql`로 N 디바이스 × 1,000행 벌크 시드(결정적). 스케일마다 `TRUNCATE telemetry, device` 후 재시드.

## 파일
- `explain-plans.txt` — `EXPLAIN (ANALYZE, BUFFERS)` 4건: naive(1M) + DISTINCT ON(1M/5M/10M).
- `k6-latest-read.txt` — k6 `telemetry-latest-read.js` 2건(1k 디바이스, VUS=1·20).
- 스크린샷: `../img/m4a-read-1k-vus1.png`(캡쳐 대시보드, VUS=1) · `../img/m4a-read-1k-vus20.png`(VUS=20).

## 요약

EXPLAIN 단일 조회:
| 쿼리 | 행수 | Execution | 비고 |
|---|---|---|---|
| ① naive 상관 서브쿼리 | 1M | 8,748 ms | SubPlan `loops=1,000,000`, 전부 shared hit |
| ② DISTINCT ON | 1M | 813 ms | Incremental Sort ← Merge Append(인덱스), shared hit |
| ② DISTINCT ON | 5M | 6,431 ms | 동일, first-touch dirtied 60,560 |
| ② DISTINCT ON | 10M | 1,438,707 ms (24분) | 플랜 전환: Parallel Seq Scan + Sort(external merge, temp ~3GB) |
| ③ LATERAL | 10M(org 1k) | 406 ms 웜 / 6,794 ms 콜드 | device당 PK 인덱스 1회(O디바이스). 콜드=random read 691 |

k6 엔드포인트 p95:
| 쿼리 | VUS=1 | VUS=20 |
|---|---|---|
| ① naive (1M, 전체) | 8.65s | 47.6s |
| ③ LATERAL (org 1k, 10M) | **35 ms** | **236 ms** |

결론: 쿼리를 결과 크기(O디바이스)에 맞춰(③ LATERAL) 8.65s→35ms(~250×), 캐시 없이. 해석은 [../M4a.md](../M4a.md).
