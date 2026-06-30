# 0007 — 메시징/저장: Redis Streams 버퍼 + DeviceType별 거버넌스 + 두 종류 리플레이

- 상태: 확정(방향) / 일부 미해결(아래 §미해결)
- 일자: 2026-06-28
- 관련: [0003](0003-feature-slice-with-core-app-split.md)(DeviceType 전략), [0004](0004-ports-only-at-improvement-seams.md)(수집 포트), [ROADMAP 보류표](../ROADMAP.md), [measurements/M1.md](../measurements/M1.md)

## 맥락
디커플링·fan-out·리플레이 요구를 정리하고, **DeviceType별 데이터 거버넌스**를 저장 아키텍처에 반영한다.
대외 도메인이 위치정보라 보존 최소화가 1급 제약(CLAUDE.md §3.5). "텔레메트리는 곧 Kafka"라는 관성적 선택을 거부하고 규모·트레이드오프로 고른다.

## 결정 요약
1. **fan-out 브로커는 Kafka가 아니라 Redis Streams.** 디커플링·fan-out엔 Redis Streams의 Consumer Group으로 충분하고, Locus 규모(시뮬레이터 부하·로컬 Docker)에선 운영·메모리 부담이 가볍다. **보존/리플레이/처리량이 한계를 넘으면 Kafka로 전환**(측정 근거로).
2. **Redis Stream = 단기 처리 버퍼**(장기 보존소 아님). `MAXLEN`으로 길이 절단. 리플레이·보존 책임은 **뒤단 영속 저장소**가 진다.
3. **리플레이는 두 종류, 저장소를 공유하지 않는다**(운영 리플레이 / 도메인 리플레이 — 아래).
4. **DeviceType이 "데이터가 파이프라인 어디까지 흐를 수 있는가(도달 범위)"와 보존 정책을 결정하는 축**이다. 추상화가 행동뿐 아니라 **데이터 거버넌스 도달범위**까지 가른다.
5. **모바일 폰 도메인은 도메인 리플레이(미션 아카이브) 경로 자체가 없다.** → 위치 장기보관 문제를 "정책으로 막는" 게 아니라 **"구조상 경로 없음"으로 해결.**

## 데이터 흐름 (목표 — 마일스톤별 점증 구축)
```
Device ──(uplink)──▶ Redis Stream (단기 버퍼, MAXLEN 절단)
                          ├─[CG: storage]   ──▶ raw telemetry 테이블 (영속, 단기 보존)
                          ├─[CG: monitoring]──▶ 실시간 모니터링 푸시
                          └─[CG: geofence]  ──▶ 지오펜스 판정 엔진
raw telemetry 테이블 ──▶ [운영 리플레이 원천] (모든 DeviceType 공통)
        └─(로봇만)──▶ 미션 단위 집계 ──▶ Mission Archive (장기 보존, 도메인 리플레이)
```
- **at-least-once / 복구**: `XACK`/`XPENDING`/`XCLAIM`, **컨슈머 멱등성 필수**.
- Stream 절단 과거는 리플레이 대상 아님(리플레이는 영속 계층에서).

## 리플레이 두 종류

| | 운영 리플레이 | 도메인 리플레이 |
|---|---|---|
| 목적 | 장애·버그 누락 메시지 재처리·상태 복구 | 미션 종료 후 경로 재구성·사후분석(제품 기능) |
| 대상 | 가공 전 raw 텔레메트리 | 미션 단위 정리 형태 |
| 보존 | 짧게(며칠~몇 주) | 길게(몇 달~몇 년) |
| 읽기 | "특정 시점 이후 순서대로"(시간 범위 스캔) | "이 미션의 이 디바이스 구간"(미션 ID/시계열) |
| 핵심 | 순서 + 멱등성 | 조회 효율·장기 비용 |
| 범위 | **모든 DeviceType** | **로봇 등 정당화되는 타입만 — 폰 제외** |

## DeviceType별 보존 정책

| 구분 | Redis Stream(버퍼) | raw 테이블(운영 리플레이) | Mission Archive(도메인 리플레이) |
|---|---|---|---|
| 모바일 폰 | 단기, 처리 후 즉시 제거 | 짧은 TTL + 강제 삭제/익명화 | **경로 없음(미생성)** |
| 로봇 | 단기 | 단기 보존 | 장기 보존 + 리플레이 허용 |

> 폰: 위치정보법·개인정보보호법 맥락 보존 최소화, 미성년 위치면 더 엄격. **"폰 데이터는 장기 저장 경로가 설계상 존재하지 않는다"**고 말할 수 있는 구조가 목표.

## 기각된 대안 — Kafka를 fan-out 브로커로 (지금)
- fan-out·디커플링 이득은 Redis Streams Consumer Group으로 **이미 확보**(이득 중복).
- 최대 처리량은 **싱크(MySQL 배치 insert)** 가 정하지 브로커가 아니다([M1](../measurements/M1.md)) → Kafka로 최대 처리량이 오르지 않음.
- Kafka 고유 가치(대용량 보존·로그 리플레이·다수 파티션 병렬)는 **이 규모에서 회수 안 됨** + 운영·메모리 비용만.
- → **지금은 Redis Streams, Kafka는 "Streams를 못 버틸 때"의 측정-게이트 전환 대상.** ("텔레메트리는 곧 Kafka"라는 관성적 선택 대신 의도적 선택 + 전환 기준을 측정으로 제시하는 게 핵심.)

## 수집 전송 — MQTT 추가 (2026-06-30, 계층 구분 명확화)
> **MQTT(수집 전송) ≠ Redis Streams(내부 fan-out 브로커).** 다른 계층이라 공존한다.

| 계층 | 역할 | 선택 |
|---|---|---|
| **수집 전송**(디바이스 → 서버 uplink) | 디바이스가 텔레메트리를 보내는 프로토콜 | HTTP POST(현재) **+ MQTT 추가** |
| **내부 fan-out**(서버 안 소비자 분기) | DB라이터·실시간푸시·지오펜스가 각자 소비 | Redis Streams(위) |

- **MQTT 추가 이유**: IoT/센서·디지털트윈 도메인의 **표준 수집 프로토콜**. 저전력·간헐연결·QoS(0/1/2)·last-will이 폰/로봇 uplink에 맞다. HTTP는 유지(웹·간단 클라이언트), MQTT는 디바이스 전송 경로로 병행.
- **위치**: `app.telemetry`에 MQTT 수신 어댑터 추가 → 기존 `TelemetryIngestService`(조립·검증)로 합류. 수집 *입구*만 늘리는 것, 적재 경로(포트·배치)는 공유.
- **Kafka와의 구분**: MQTT는 *전송*, Kafka는 *내부 스트리밍 브로커*. MQTT 도입이 "Kafka 대신"이 아니다. Kafka는 여전히 측정-게이트 보류(위 §기각).

## 구현 점검 — 버퍼 내 PII (M6과 연결)
단기 버퍼라도 위치 좌표 원본이 머무는 지점을 점검한다.
- Redis 영속화(RDB/AOF)에 위치가 묻어가는지.
- 백업 경로에 PII 포함되는지.
- 처리 완료 후 `XACK`/필요시 `XDEL`로 즉시 정리하는지.
- **"버퍼에 PII가 얼마나 오래 머무는가"에 답할 수 있어야 함.**

## 영향 (점증 구축 — 지금 다 만들지 않음)
- **M1/M2**: 외부 브로커 없이 **인메모리 큐 + 배치**(가장 단순, 처리량 증명). 최대 처리량을 올리는 요인은 배치 insert.
- **M4**: Redis 도입(어차피 `LatestStateLookup` 캐시용) → **같은 Redis가 캐시 + Streams 브로커** 두 일. 새 인프라 아님(§3.4 안 깸). 인메모리 큐 → Stream Consumer Group(`storage`) + `monitoring` 푸시로 승격.
- **M5**: 지오펜스 = 같은 Stream의 또 다른 Consumer Group.
- **M6**: raw TTL·폰 강제 삭제/익명화·버퍼 PII 점검.
- **페이즈2(M9~, 로봇)**: Mission Archive + 도메인 리플레이 경로(폰엔 미생성).
- `core.strategy`의 DeviceType이 **거버넌스 도달범위**도 가른다(0003 추상화의 새 명분).

## 미해결 / 다음 결정 필요
- **Mission Archive를 raw에서 어떻게 뽑나**: Stream 컨슈머 직접 적재 vs 배치 집계 → MissionType 설계와 엮임(페이즈2).
- raw telemetry **보존 기간(TTL) 수치** 확정(M6 — TimescaleDB retention policy로 구현, [0008](0008-telemetry-store-timescaledb.md)).

> **갱신(2026-06-30)**: "저장소를 시계열로 바꿀지"는 [ADR 0008](0008-telemetry-store-timescaledb.md)에서 **TimescaleDB 전환으로 확정**(M1 측정이 트리거). 여기 0007의 "raw 테이블"은 그 TimescaleDB 하이퍼테이블을 가리킨다.
