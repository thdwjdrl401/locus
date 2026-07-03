# M-MQTT — MQTT 수집 경로 (디바이스 uplink)

> 상태: **측정 프로토콜 확정(2026-07-03), 측정 예정.** 트리거: IoT 표준 수집 전송 추가([ADR 0007 §MQTT](../decisions/0007-messaging-storage-redis-streams-and-governance.md)). MQTT 어댑터가 브로커를 구독해 `TelemetryRequest`로 역직렬화·검증한 뒤 HTTP와 같은 `TelemetryIngestService`로 합류한다(core 변경 0).
> 측정: 박스(SUT)에서 `mode=stream` 실 기동(Mosquitto·Redis·TimescaleDB) + 맥에서 emqtt-bench 부하. 절대수치는 이 HDD 박스 종속 → 구조·설정 간 비율로 해석. 대시보드 `locus-mqtt`.

## 목표
1. **인입 처리량**: 단일 Paho 콜백 스레드 인라인 처리가 업링크 10k SLO를 따라가는가. 못 따라가면 병목이 인입 스레드인지(→ 익스큐터 오프로드) 하류인지 분리한다.
2. **무손실**: 받은 것(subscriber 콜백 도달)이 전부 DB에 남는가. 브로커 경계(발행 대비 도달)의 손실을 별도로 귀속한다.

## 구성 — 왜 stream 모드인가
측정은 `INGEST_MODE=stream`으로 고정한다. 근거:
- **목표 아키텍처의 실제 경로.** MQTT 인입 → 검증 → `XADD telemetry.stream` → storage 컨슈머 배치 적재(ADR 0007). M4b가 이 하류를 지속 10k 무손실로 특성화([M4b.md](M4b.md))했으므로, 이번 측정의 변수는 **인입 계층(MQTT 어댑터)** 하나로 좁혀진다.
- **하류가 인입 병목을 안 가린다.** `StreamIngestWriter.submit`은 XADD 한 번(싸다)이라, 콜백 스레드가 못 따라가면 그 원인이 인입 직렬화임을 드러낸다. `direct` 모드로 돌리면 동기 트랜잭션 DB 쓰기(fsync)가 병목이라 M1을 재측정하는 꼴이고 MQTT 인입 이야기가 안 나온다.

고정 설정: `INGEST_WORKERS=4`·`INGEST_STREAM_MAXLEN=400000`(M4b 무손실값) · `MQTT_ENABLED=true` · `MQTT_QOS=1`.

## 손실 오라클 — 3경계 정산
`received − dropped == DB` 하나로는 부족하다. 손실 지점이 세 경계에 있고, 앱 카운터 하나는 그중 하나만 본다.

```
emqtt-bench 발행(P)
   │  ← 경계 A: 브로커 + subscriber 인입 (Mosquitto max_queued_messages 기본 1000, QoS·인플라이트 창, TCP 백프레셔)
   ▼
locus.mqtt.received (R)      콜백이 실제로 끌어온 수
   │  ← 경계 B: 어댑터 (역직렬화·id 정합·검증·XADD 실패 → locus.mqtt.dropped)
   ▼
accepted = R − mqtt.dropped  XADD까지 성공
   │  ← 경계 C: 스트림 트림 + storage 적재 (MAXLEN 절단, ON CONFLICT dedup)
   ▼
DB count
```

정산 규칙:
- **경계 B·C(앱 내부)**: `MAXLEN 400K`(M4b: warm-up lag < 400K라 트림 유실 0) + 발행 timestamp가 메시지마다 유일(`%TIMESTAMP%` ms) + QoS1 재전송 없음이면 dedup 0 → **`DB == R − mqtt.dropped`**.
- **경계 A(브로커)**: `R < P`의 차이 = 브로커·인입 손실. `$SYS/broker/publish/messages/dropped`와 emqtt-bench 발행 총계로 귀속한다. **"받은 것만으로 무손실"이라 결론내지 않는다** — 콜백에 도달 못 한 손실은 어떤 앱 카운터에도 안 잡힌다.

주의 — 손실 지점이 `mqtt.dropped`로 안 흘러오는 경우(이번 프로토콜이 stream·QoS1·MAXLEN 400K로 이걸 0으로 만드는 이유):
- **큐 모드**: `QueuedIngestWriter`가 큐 가득 참을 `locus.ingest.dropped`(별도 카운터)로만 올리고 예외 없이 반환 → 어댑터는 accepted로 계산. stream 모드로 고정해 회피.
- **스트림 트림**: XADD 성공 후 storage가 읽기 전 잘린 엔트리는 예외 없이 사라짐 → MAXLEN을 peak lag 위로(400K).
- **QoS1 중복**: 콜백 느리면 PUBACK 지연 → 브로커 재전송 → `received++`이나 ON CONFLICT로 DB 행 안 늘고 예외 없음 → `DB < accepted`. 부하를 **1만 디바이스 × 1Hz**로 구성해 인플라이트 포화·재전송을 억제한다.
- **복합 PK 해상도 상한**: DB 키가 `(device_id, recorded_at)` ms 해상도라 한 디바이스가 >1000 msg/s면 같은 ms 충돌 → 정상 메시지가 중복으로 드롭. 1만 디바이스 × 1Hz는 디바이스당 1 msg/s라 무관.

DB count가 진실이다(카운터는 검증용). `locus.ingest.inserted`는 `batch.size()` 증가라 ON CONFLICT 미삽입분만큼 과대 → 오라클엔 `SELECT count(*) FROM telemetry`를 쓴다.

## 부하 모델 — emqtt-bench
1Hz 디바이스 N대 = **N clients × 발행주기 1000ms = N msg/s**(M4b capacity 열린 모델의 MQTT 대응). 클라이언트 하나 = 디바이스 하나 = 토픽 `telemetry/{seq}` 하나. 부하 정의는 `load/`에 스크립트로 커밋(k6 `telemetry-capacity.js`와 같은 위치·역할):

```bash
# 맥에서. 박스·이미지·단계는 env로 오버라이드. 페이로드는 load/mqtt-payload.json.tmpl.
./load/mqtt-ramp.sh                                   # 런1 램프 2k→10k
./load/mqtt-sustain.sh                                # 런3 지속 flat 10k 5분
STEPS="2000 5000 10000" STEP_SECONDS=90 ./load/mqtt-ramp.sh   # 오버라이드 예
```
- deviceId는 페이로드에서 생략 → 어댑터가 토픽(`telemetry/%i`) 마지막 세그먼트로 채움(스푸핑 방지 계약, `MqttTelemetryHandler`).
- **함정 1 — 템플릿 변수는 `template://` 파일 모드에서만 치환.** `-m`에 리터럴 JSON을 주면 `%TIMESTAMPMS%`가 글자 그대로 나가 Jackson 파싱 실패로 **전량 드롭**. 그래서 페이로드를 파일로 마운트하고 `-m 'template:///payload.json'`(emqtt-bench `pub --help` 확인).
- **함정 2 — `%TIMESTAMP%`(초) 아님 `%TIMESTAMPMS%`(밀리초).** 앱이 숫자를 epoch-ms로 읽어(`read-date-timestamps-as-nanoseconds=false`) 초값을 주면 1970년→`@ValidTimestamp`(과거 7일) 전량 드롭. ms는 메시지마다 갱신되어 디바이스당 `recorded_at` 유일 → dedup 0.
- **함정 3 — `--ulimit nofile`은 `docker run`에 준다.** 호스트 `ulimit -n`은 컨테이너 안 emqtt-bench에 안 넘어가 커넥션 ~1000에서 `emfile`. 스크립트가 `--ulimit nofile=1048576:1048576`으로 처리.
- emqtt-bench pub은 한 프로세스에서 도착률 램프를 못 하므로 **램프 = `-c` 단계별 런**(각 단계 기본 120s, warm-up 흡수 후 steady).

## 런 계획
| 런 | 구성 | 보는 것 |
|---|---|---|
| **런1 (램프)** | `-c` 2k→4k→6k→8k→10k, 각 90s. 단일 콜백 스레드 인라인(현재 코드) | 인입이 어디서 plateau 하는가. `received`율 < 발행율 + 앱 CPU 1코어 고정 + `$SYS` 아웃바운드 큐/드롭 → **병목 = 인입 스레드** |
| **런2 (막히면)** | 콜백을 바운드 익스큐터로 오프로드(즉시 PUBACK, 비동기 처리). 큐 가득 참은 새 드롭 카운터로 계측 | 오프로드로 인입 상한이 올라가는가. 오프로드가 앱 내부에 드롭 지점을 다시 만드므로 경계 B에 합산 |
| **런3 (지속)** | flat 10k · 5분 · 워커 4 · MAXLEN 400K | 정상상태 무손실. 오라클 `DB == R − mqtt.dropped`, `R vs P` 귀속 |

런1에서 QoS1 인플라이트 창(Paho 기본 max_inflight)이 콜백 지연에 막혀 브로커 큐가 차는지 확인한다 — 그게 인입 스레드 병목의 신호다.

## 측정 전 준비
- [x] **대시보드 `locus-mqtt`에 stream 적재 패널(row 4)** — accepted/s vs inserted/s(벌어지면 스트림 적체·트림 위험) · 배치 flush avg/max · flush 에러율. 앱이 내보내는 실 메트릭(`locus_ingest_inserted_total`·`locus_ingest_flush_seconds`·`locus_ingest_flush_errors_total`)만 사용.
- [x] **관측 스크립트 확정** — `scripts/mqtt-observe.sh`(박스)가 exporter 없는 값을 런별 폴더 `M-MQTT-raw/<RUN>/`에 남긴다: `sys.log`(브로커 $SYS·경계 A) · `stream.log`(XLEN·lag·경계 C) · `vmstat.log`(메모리 궤적·OOM) · `snap.log`(카운터+DB 정산 타임라인). XLEN/lag는 앱이 게이지를 안 내보내 redis-cli로 봄(redis-exporter는 §3.4 인프라 하나씩이라 안 올림).
- [x] **부하 스크립트 확정** — `load/mqtt-ramp.sh`·`load/mqtt-sustain.sh`·`load/mqtt-payload.json.tmpl`. 템플릿 변수 치환(파일 모드)·`%TIMESTAMPMS%`·`--ulimit` 함정 반영.
- [ ] **emqtt-bench 이미지 digest 핀** — 현재 `emqx/emqtt-bench:latest`(id `ae7f2d56cd49`). `docker inspect`로 sha256 확인해 스크립트 `IMG`·여기에 고정(재현성, 떠다니는 태그 금지).
- [ ] `INGEST_MODE=stream`·`MQTT_ENABLED=true`·`INGEST_WORKERS=4`·`INGEST_STREAM_MAXLEN=400000`를 `.env`에 넣어 `scripts/run-app.sh`가 앱에 전달.

## 실행 절차
박스:
```bash
cd locus && git pull
docker compose up -d                 # timescaledb + redis + mosquitto + node-exporter
./gradlew bootJar && ./gradlew --stop
# .env: INGEST_MODE=stream, INGEST_WORKERS=4, INGEST_STREAM_MAXLEN=400000, MQTT_ENABLED=true, MQTT_QOS=1
scripts/run-app.sh
free -h                              # Swap used = 0 (HDD 스왑 = 측정 무효)
```
관측은 부하 직전에 박스에서 띄운다(런별 폴더로 저장, Ctrl-C 종료):
```bash
RUN=ramp1 scripts/mqtt-observe.sh    # → docs/measurements/M-MQTT-raw/ramp1/{sys,stream,vmstat,snap}.log
```
맥: `docker-compose.monitoring.yml`(Prometheus+Grafana) 기동 후 `./load/mqtt-ramp.sh`. 상세 2-머신 절차는 [RUNBOOK](RUNBOOK.md).

정산(런 종료 후): `snap.log` 마지막 줄로 `DB증가분 == received − dropped`(경계 B·C) 확인, `received vs emqtt-bench sent 총계`(발행 P) 차이는 `sys.log`(경계 A)로 귀속. 트림 유실은 `stream.log`의 `lag`이 400K를 넘겼는지, OOM은 `vmstat.log`의 `free` 바닥으로 본다.

---

## 결과
_측정 후 채움 — 환경 블록 · 런1/2/3 표 · 3경계 정산 수치 · Grafana 스크린샷 · 해석._
