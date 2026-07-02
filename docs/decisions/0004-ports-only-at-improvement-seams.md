# 0004 — 포트는 개선 이음새에만 (헥사고날 부분 차용)

- 상태: 확정
- 일자: 2026-06-19

## 결정
헥사고날을 전면 채택하지 않되([0003](0003-feature-slice-with-core-app-split.md)),
**출력 포트(interface)를 "구현을 갈아끼운다고 계획서가 명시한 이음새"에만** 둔다.

## 근거
계획서의 핵심 루프는 **"순진하게 먼저 → 측정 → 구현 갈아끼움 → 다시 측정"**이다.
"호출부는 그대로, 구현만 교체"가 바로 포트&어댑터의 정의 → 개선 이음새에 포트가 정확히 맞는다.
포트를 두면 **설정 토글로 구현을 갈아끼워**(호출부 무변경, 재시작 토글) before/after를 같은 조건에서 재현·측정할 수 있다. 실제 운용도 `@ConditionalOnProperty` 토글이다(`direct`/`queue`/`stream`, `db`/`cache`).

## 포트를 두는 지점
아래는 계획 시점(2026-06-19)에 보인 이음새다. 이 목록이 닫힌 건 아니고, **구현 교체 계획이 명시될 때 추가한다** — M-MQTT의 `MqttSubscriber`(Paho → 필요 시 HiveMQ)가 그렇게 추가된 예. 교체 계획이 없는 곳엔 여전히 두지 않는다.

| 마일스톤 | 포트 (위치) | 1차 구현 (baseline) | 2차 구현 (개선) |
|---|---|---|---|
| M2 | 수집 `TelemetryIngestPort` (`app.telemetry`) | `DirectSaveIngest` | `InMemoryQueueIngest` → (fan-out 시) `RedisStreamIngest` ([0007](0007-messaging-storage-redis-streams-and-governance.md)) |
| M4 | 최신상태 `LatestStateLookup` (`app.telemetry`/`device`) | `DbLatestStateLookup` | `RedisLatestStateLookup` |
| M5 | 지오펜스 상태 `GeofenceStateStore` (`core.engine`가 정의, 구현은 `app`) | `InMemoryGeofenceStateStore` | `RedisGeofenceStateStore` |

> M5의 포트를 `core`에 두는 이유: 엔진이 "상태를 어디 저장하는지" 모른 채 동작하게 하여 infra-free를 유지([0002](0002-single-module-with-archunit.md))하면서도 상태를 쓸 수 있다.

## 하지 않는 것
- 모든 유스케이스를 port/in으로 감싸지 않는다.
- 모든 인프라를 port/out으로 감싸지 않는다(교체되지 않는 인프라까지 추상화하면 보일러플레이트만 는다).
- 컨트롤러→서비스→JPA 레포는 슬라이스 안에서 평범하게 직접 호출.
