# disk-baseline — SUT 디스크 특성화 (fio)

> 상태: **측정 완료(2026-07-07)**. 트리거: "적재 한계가 대역폭인가"라는 질문. M0~M2-par의 적재 병목 해석을 뒷받침하는 **하드웨어 기준선**이자, 향후 SSD 전환의 **before 기준선**이다.
> 측정: 앱·DB·부하 정지 상태에서 `fio`로 DB 데이터와 같은 물리 디스크(`/dev/sda3`)를 직접 때린다. 절대수치는 이 HDD 박스 종속.
> ⚠️ 갱신(2026-07-09): 이 문서는 **durable per-commit(naive) 물리의 하드웨어 기준선**이다. 배치된 SLO 운영점(queue N4)의 실제 인입 병목은 디스크가 아니라 **박스 CPU**임이 이후 [M-http-capacity](M-http-capacity.md)에서 규명됐다(핀6→8 처리량 선형 +33%, knee에서 디스크 %util ~68% 여유). 따라서 아래 §함의의 "SSD가 유일한 큰 레버"는 **fsync-바운드(naive) 한정**이고, 배치 경로의 12–16K 천장은 SSD가 아니라 앱 요청처리 CPU가 레버다.

## 목표
적재 병목이 **디스크 대역폭(bytes/s)인지 sync 지연(fsync/s)인지**를 앱·DB를 빼고 디스크 자체로 판별한다. M2-par가 "순차 대역폭이 한계"로 기록한 서술을 이 기준선으로 검증한다.

## 결과 — 병목은 대역폭이 아니라 sync 지연이다

같은 디스크가 접근 패턴에 따라 **3자릿수** 차이로 갈린다:

| 테스트 | 대역폭 | IOPS | 지연 | %util |
|---|---|---|---|---|
| ① 순차 쓰기 (1M, direct) | **61.7 MB/s** | 58 | clat 135ms (iodepth 8) | 99.9% |
| ② 랜덤 4K 쓰기 (direct, sync 없음) | **0.69 MB/s** | 169 | clat 5.3ms | 99.6% |
| ③ durable 쓰기 (8K, fdatasync 매 쓰기) | **0.16 MB/s** | **~20** | **sync 50ms** | 99.4% |

- **①과 ③은 같은 디스크, 같은 99% util인데 대역폭이 380배 차이.** 차이는 매 쓰기 뒤 `fdatasync`(durability) 하나뿐. → `%util`은 장치가 바빴던 **시간 비율**이지 대역폭이 아니다. util 99%가 대역폭 포화를 뜻하지 않는다.
- **durable 경로의 대역폭 사용률 = 0.16 ÷ 61.7 = 0.26%.** 대역폭은 텅 비어 있고, 벽은 **초당 ~20회 durable sync(건당 50ms)** 라는 회전판 물리다.

## 세 접근 패턴의 물리

1. **순차 스트리밍(①) = 61.7 MB/s.** 헤드가 안 튀므로 대역폭이 최대. 적재에서 이 한계에 닿은 적은 없다(ADR 0008 검산: 10k 적재 ≈ 5MB/s = 이 대역폭의 ~8%).
2. **랜덤 쓰기, sync 없음(②) = 169 IOPS @ 5.3ms.** 5.3ms ≈ 5400rpm 평균 회전지연(5.55ms). 즉 랜덤 쓰기는 **회전 한 바퀴 대기**에 묶인 순수 seek/회전 바운드. 4GB 연속 파일이라 seek는 작고 회전지연이 지배.
3. **durable sync(③) = ~20/s @ 50ms.** ②(5.3ms)보다 ~10배 무겁다 — `fdatasync`가 데이터 블록 + ext4 저널 커밋 + 드라이브 캐시 플러시까지 강제(회전 여러 바퀴). write() 자체는 63µs(캐시행)이고 비용 전부가 sync 장벽에 있다. **드라이브가 fsync로 거짓말하지 않는다**(캐시가 속이면 sub-1ms) → PostgreSQL `synchronous_commit=on`이 실제로 겪는 물리와 동일 조건.

## 적재 처리량이 이 물리에서 나오는 방식

```
적재 처리량 = (초당 durable sync 횟수) × (sync당 행 수)
                    ↑ ~20/s (고정, 물리)      ↑ 배치가 키우는 값
```

- **naive**(행마다 커밋): 20 × 1 = ~20 rows/s.
- **배치**(batch 500): 20 × 500 ≈ **10,000 rows/s** — M1의 44배·M2 SLO 천장의 정체가 이 곱셈이다. 남는 99.7% 대역폭은 "더 빠른 디스크"가 아니라 **sync당 행 수를 늘려서(배치)** 꺼내 쓴다.
- **앱 실측 천장 ~13.5k(M2-par, N=8, util 93%)** 는 대역폭이 아니라 **체크포인트 랜덤 쓰기(②의 169 IOPS seek 바운드)가 순차 WAL과 단일 헤드를 경합**해 생긴다. M2-par bgwriter 실험(랜덤 쓰기 추가 → 배치 3.5ms→199ms)이 헤드 경합을 실증.

## 함의 — "1만 위로" 레버

- **압축(wal_compression·TimescaleDB 컬럼나·엔진 교체)은 이득 ~0.** 바이트를 줄이는데 바이트가 병목이 아니다(대역폭 0.26% 사용).
- **배치/group commit은 이미 소진.** sync당 행을 더 키우면 체크포인트 랜덤 I/O가 다음 벽.
- **SSD는 fsync-바운드(naive) regime 한정 레버.** fsync 50ms → ~0.5ms(durable IOPS ~100배), 랜덤 seek 경합 소멸 → ③·② 두 벽이 동시에 무너진다. **단 배치가 이미 fsync를 amortize한 SLO 운영점(12–16K)은 CPU 바운드**라([M-http-capacity](M-http-capacity.md)) SSD로 그 천장은 안 오른다 — 거기 레버는 앱 핫패스 CPU 절감. 이 문서는 fsync 물리의 before 기준선.
- (부차) `synchronous_commit=off` — durability를 포기해 fsync 벽 자체 제거. 텔레메트리는 유실 허용이라 도메인상 후보이나 별개 결정.

## 측정 환경·지표

| 항목 | 값 |
|---|---|
| SUT(박스) | i7-6700HQ 4c/8t · 8GB · 5400rpm HDD |
| 디스크 | `/dev/sda3` ext4, `/`에 마운트 (DB 볼륨 `postgres-data`와 동일 물리 디스크) |
| 상태 | 앱·DB·부하 **정지**, 스왑 0 |
| 도구 | fio 3.28, `--direct=1`(페이지 캐시 우회), 매 런 `--unlink=1` |
| 날짜 | 2026-07-07 |

측정 정직성: ③에서 두 번 반복 → `IOPS 19, sync 50ms` 재현. ①의 `bw min 20 ~ max 72 MB/s`는 플래터 존별(바깥 빠름) 변동. fio가 16% 찬 FS의 새 파일에 써서 **순차(①)는 약간 낙관적**일 수 있으나, 결론(sync 지연 벽)은 회전지연이 지배해 파일 배치와 무관.

## 재현

```bash
sudo apt-get install -y fio
mkdir -p ~/fio-test ~/fio-out && cd ~/fio-test
df -T ~/fio-test          # ext4/xfs 확인(tmpfs면 무효), DB 정지 확인

# ① 순차 쓰기 대역폭
fio --name=seqwrite --directory=/root/fio-test --rw=write --bs=1M --size=8G \
    --ioengine=libaio --direct=1 --iodepth=8 --end_fsync=1 --unlink=1 | tee ~/fio-out/1-seqwrite.txt
# ② 랜덤 4K 쓰기 (seek/회전 바운드)
fio --name=randwrite --directory=/root/fio-test --rw=randwrite --bs=4k --size=4G \
    --ioengine=libaio --direct=1 --iodepth=1 --runtime=60 --time_based --unlink=1 | tee ~/fio-out/2-randwrite.txt
# ③ durable fdatasync (PostgreSQL synchronous_commit=on 물리)
fio --name=fsynctest --directory=/root/fio-test --rw=write --bs=8k --size=1G \
    --ioengine=psync --fdatasync=1 --iodepth=1 --runtime=60 --time_based --unlink=1 | tee ~/fio-out/3-fsync.txt
```

원본 출력은 박스 `~/fio-out/{1,2,3}-*.txt` → 커밋 시 `docs/measurements/disk-baseline-raw/`로 복사.

## 교차 참조 / 정정 대상
- [ADR 0008](../decisions/0008-telemetry-store-timescaledb.md) §검산("10k ≈ 5MB/s = 대역폭 5%, 병목은 랜덤 쓰기") — 이 기준선이 실측으로 뒷받침.
- [M2-par.md](M2-par.md)·[PERFORMANCE.md](../PERFORMANCE.md)의 **"디스크 순차 쓰기 대역폭이 한계"** 서술은 부정확 — 실제 벽은 **fsync/seek 지연**(대역폭 0.26% 사용). 표현 정정 필요(§2.6).
- [M-http-capacity.md](M-http-capacity.md): 배치된 운영점에선 fsync/seek조차 바인딩 아님 — **CPU 바운드**(핀6→8 선형). 진단 사슬: M2-par(대역폭) → 이 문서(fsync 지연) → M-http-capacity(CPU). **regime 구분이 핵심**(naive=디스크, 배치=CPU).
- [M0-raw/README.md](M0-raw/README.md)의 `f/s≈40 @ f_await 25ms`와 같은 자릿수(fio ③은 write+fdatasync라 2배 무거워 ~20/s @ 50ms).
