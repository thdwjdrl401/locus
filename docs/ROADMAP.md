# 마일스톤 → 트리 위치 매핑

각 마일스톤이 코드의 **어디에 떨어지는지**와 그때 **추가되는 인프라/포트**. 상세는 계획서.

## 페이즈 1 — 수집·조회·모니터링

| M | 주제 | 주요 위치 | 추가 인프라 | 포트(0004) |
|---|---|---|---|---|
| **M0** | 모델·검증·시뮬레이터·측정 | `core.domain`, `app.telemetry`, `app.device`, `app.simulator` | MySQL | — |
| **M1** | 부하·런타임 진단 | (코드 변경 적음) k6, JVM 옵션, HikariCP | — | — |
| **M2** | 메시지 큐·배치 적재 | `app.telemetry`, `app.config(KafkaConfig)` | **Kafka** | 수집 `TelemetryIngestPort` |
| **M3** | 추상화 검증 | `app.device`(TagHandler/TagProfile), `core.strategy` enum | — | — |
| **M4** | 실시간 푸시·최신상태 캐시 + **인증/식별** | `app.telemetry`/`device`, `app.auth`(신규), `app.user`(신규), `app.config(Redis/WebSocket)` | **Redis** | `LatestStateLookup` |
| **M5** | 도달/이탈 판정 엔진 | `core.engine`, `app.geofence`(신규) | (상태저장 Redis 재사용) | `GeofenceStateStore` |
| **M6** | 민감정보 보호 | `core.domain`(암호화 컬럼), `app.support`(마스킹), 스케줄러 | — | — |
| **M7** | 대용량 조회·복제 | `app.telemetry`(커서), `app.config(라우팅DS)` | MySQL 읽기 복제 | — |
| **M8** | 컨테이너·k8s | `Dockerfile`, k8s manifests | (앱 컨테이너화) | — |

## 페이즈 2 — 미션 (다운링크)

| M | 주제 | 주요 위치 | 포트/엔진 재사용 |
|---|---|---|---|
| **M9** | 명령 경로 + 미션 도메인 | `app.mission`(신규), `core.strategy(MissionType)`, `core.domain(Mission)` | M5 `ReachEvaluator` 재사용 |
| **M10** | 미션 동시성·정합성 | `app.mission`(락 전략 비교) | 낙관/비관/Redisson |
| **M11** | (선택) 미션 타입 추가 / MQTT | `core.strategy` 두번째 구현, MQTT 수집 | — |

## 규칙
- 슬라이스 폴더(`geofence`/`mission`/`auth`/`user`)는 **해당 마일스톤에서 생성**한다(미리 빈 폴더 X).
- 마일스톤마다 `docs/measurements/Mx.md`에 before/after 수치를 남긴다.
- 한 마일스톤에 인프라 둘 이상 동시에 올리지 않는다.

## 보류된 결정 (의도적으로 지금 안 정함)
과잉결정을 피한다. 아래는 해당 마일스톤의 설계 시점에 정한다(계획서 "순진하게 먼저" 원칙).

| 주제 | 결정 시점 | 지금 정한 방향(가벼운 가드레일) |
|---|---|---|
| 인증·식별 | **M4** | • 인증/식별은 **app 계층**(core 아님). • 보안 계층은 **공통 Principal**(디바이스·교사 둘 다 인증 주체). • 도메인은 **`Device` ≠ `User`** 분리. • 디바이스=장수명·폐기가능 토큰, 사람=단명 JWT+refresh, 즉시폐기는 Redis 블랙리스트(M6 민감성과 연결). • 세부(JWT vs opaque·토큰 수명·enrollment 모델)는 **M4에서**. |
| `Device` enrollment 필드 | **M4** | M0엔 넣지 않는다(투기 금지). 인증 설계 때 컬럼 추가(`ddl-auto`로 비용 ≈ 0). |
