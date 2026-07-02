# M4b-raw — 원본 측정 데이터

`docs/measurements/M4b.md`의 근거 원본. 각 파일은 박스(SUT)에서 `mode=stream` 실 기동 + 맥 k6 부하의 실제 출력이다(k6 요약 · `XINFO GROUPS`/`XLEN` · DB count · Redis `INFO`).

공통 환경: SUT i7-6700HQ 4c/8t · 8GB · 5400rpm HDD(코어 0–5·힙 1.5G 격리) · TimescaleDB 2.17.2-pg16(shared_buffers 2GB) · Redis 7.4(maxmemory 256mb, noeviction, 영속화 끔) · JDK21 G1GC · k6 열린 모델 `telemetry-capacity.js` · 디바이스 풀 12,000 · 유선 LAN · 2026-07-02.

유실 오라클: `유실 = accepted(202) − DB count`. 충돌(`ON CONFLICT`)은 12k 풀에서 ~0이라 유실=트림. 재시작 오라클: `DB == k6 202 성공수`(무손실) 이고 `DB ≤ accepted`(무중복).

| 파일 | 워커 | MAXLEN | 부하 | accepted(202) | DB | 유실 |
|---|---|---|---|---|---|---|
| [00-oom-maxlen1m.txt](00-oom-maxlen1m.txt) | 1 | 1,000,000 | ramp 2–12K | 580,285 | 580,285 | 요청 35% HTTP 500(Redis OOM) |
| [01-sweep-w1-maxlen200k.txt](01-sweep-w1-maxlen200k.txt) | 1 | 200,000 | ramp 2–12K | 1,679,140 | 1,100,137 | 579,003 (34.5%) |
| [02-sweep-w2-maxlen200k.txt](02-sweep-w2-maxlen200k.txt) | 2 | 200,000 | ramp 2–12K | 1,678,309 | 1,448,413 | 229,896 (13.7%) |
| [03-sweep-w4-maxlen200k.txt](03-sweep-w4-maxlen200k.txt) | 4 | 200,000 | ramp 2–12K | 1,677,821 | 1,677,821 | 0 |
| [04-flat-w4-maxlen200k.txt](04-flat-w4-maxlen200k.txt) | 4 | 200,000 | flat 6/7/8/9K | 1,566,855 | 1,566,855 | 0 (admission-bound ~8K) |
| [05a-flat10k-90s-maxlen200k.txt](05a-flat10k-90s-maxlen200k.txt) | 4 | 200,000 | flat 10K 90s | 669,494 | 669,494 | 0 (warm-up만 측정) |
| [05b-flat10k-5min-maxlen200k.txt](05b-flat10k-5min-maxlen200k.txt) | 4 | 200,000 | flat 10K 5min | 2,794,840 | 2,793,798 | 1,042 (0.037%) |
| [06-flat10k-5min-maxlen400k.txt](06-flat10k-5min-maxlen400k.txt) | 4 | 400,000 | flat 10K 5min | 2,780,515 | 2,780,515 | 0 |
| [07-restart-kill-maxlen400k.txt](07-restart-kill-maxlen400k.txt) | 4 | 400,000 | flat 6K, kill@40s | 527,928 | 527,928 | 0 (무손실·무중복) |

대시보드 스크린샷(Grafana `locus-m4b-stream`)은 별도. 절대수치는 이 HDD 박스 종속 → 구조·설정 간 비율로 해석.
