# 마일스톤 → 트리 위치 매핑

각 마일스톤이 코드의 **어디에 떨어지는지**와 그때 **추가되는 인프라/포트**.

> 문서 곳곳의 "계획서"는 저장소 밖의 초기 기획 문서(비공개)다. 핵심 원칙은 `CLAUDE.md`·[conventions.md](conventions.md)에 반영돼 있어 본문 이해에 필수는 아니다.

## 목표 SLO + 도메인 포지셔닝 (2026-06-30)
**도메인: IoT/센서 텔레메트리 수집·처리 파이프라인 + 실시간 데이터 연계.**

**목표 SLO (설계 목표 — 도메인 요구가 고정돼 있지 않아 직접 정함):**
| 경로 | 목표 | 현재 | 달성 수단 |
|---|---|---|---|
| 업링크 적재 | **10,000 req/s** | **달성** — 지속 10k 무손실([M2-par](measurements/M2-par.md)·[M2-sustain](measurements/M2-sustain.md)) + 전 구간 60분+ 무손실([M-e2e-soak](measurements/M-e2e-soak.md)) | 배치 적재 + 순차 저장(TimescaleDB) + 워커 병렬화 + Streams fan-out |
| 조회 최신 | 1만 대 실시간 | 조회 p95 35ms([M4a](measurements/M4a.md) 쿼리 재설계) + WebSocket push([M4b](measurements/M4b.md)). 잔여: 1만 마커 브라우저 렌더(M4b-A) | LATERAL 쿼리 + Streams push |
| 다운링크 명령 | ~500~1,000대 | — (페이즈2) | 정합성(멱등·ack·순서, 페이즈2) |
> 가치는 **측정된 서사**(랜덤→순차 X배)지 10k 숫자 자체 아님. 실측 최대치(헤드룸)도 함께 측정.

**다룰 핵심 기술** (이 도메인에서 자연히 요구되는 것):
- Java/REST = M0~ ✅ · **WebSocket** = M4 · **PostgreSQL**(TimescaleDB) = M2 · **MQTT 수집** = M-MQTT
- 시계열 DB(TimescaleDB) = M2 · Docker ✅/K8s = M8 · 클라우드 = 설계
- **다루지 않음: GC/메모리 튜닝** — 이 워크로드는 I/O가 병목이라 비병목(측정 근거).

## 페이즈 1 — 수집·조회·모니터링

| M | 주제 | 주요 위치 | 추가 인프라 | 포트(0004) |
|---|---|---|---|---|
| **M0** | 모델·검증·시뮬레이터·측정 ✅ | `core.domain`, `app.telemetry`, `app.device`, `app.simulator` | MySQL | — |
| **M1** | 적재 포화점 높이기(fsync 분할) ✅ | `app.telemetry`(인메모리 큐+배치), MySQL 설정, k6 | — | 수집 `TelemetryIngestPort`(A2) |
| **M2** | **TimescaleDB 전환**(순차 저장) ✅ ★PostgreSQL+시계열 | `core.domain`/`app.telemetry` 영속 이식, `TelemetryBatchDao` | **TimescaleDB**(PostgreSQL 확장) | (배치 DAO 재사용) |
| **M4** | **실시간: Redis 캐시 + WebSocket 푸시** ✅(잔여: 인증) ★WebSocket ([스펙](specs/M4-realtime-read-path.md)) | `app.telemetry`/`device`, `app.config(Redis/WebSocket)` | **Redis**(캐시+Streams fan-out, [0007](decisions/0007-messaging-storage-redis-streams-and-governance.md)) | `LatestStateLookup` |
| **M-MQTT** | **MQTT 수집 경로** ✅ ★브로커 | `app.telemetry`(MQTT 수신 어댑터→기존 `IngestService` 합류) | **MQTT broker**(Mosquitto) | 수집 입구만 확장 |
| **M3** | 추상화 검증(디바이스 타입) ✅ | 봉투 일반화(공통칸+metrics 자유칸)·게이트를 `DeviceTypeHandler.gate` 전략으로·AMR 추가(`app.device`/`app.simulator`). core diff=enum 1줄+게이트 훅 | — | — |
| **M5** | 도달/이탈 판정 엔진 🔄(슬라이스1: 엔진+CG+가시화) | `core.engine`(ReachEvaluator/RadiusEvaluator/ReachTransition), `app.geofence`(신규) | (Redis Stream `geofence` CG) | `GeofenceStateStore` |
| **M6** | 민감정보 보호·보존 | `core.domain`(암호화), `app.support`(마스킹) | — (보존은 TimescaleDB retention 흡수, [0008](decisions/0008-telemetry-store-timescaledb.md)) | — |
| **M7** | 대용량 조회·복제 | `app.telemetry`(커서), 라우팅DS | (파티셔닝은 TimescaleDB 하이퍼테이블 흡수) PostgreSQL 읽기복제 | — |
| **M8** | 컨테이너·**k8s**(우대) | `Dockerfile`, k8s manifests | (앱 컨테이너화) | — |
| (보류) | 인증/식별 | `app.auth`/`app.user` | — | (보류표) |

## 페이즈 2 — 미션 (다운링크)

| M | 주제 | 주요 위치 | 포트/엔진 재사용 |
|---|---|---|---|
| **M9** | 명령 경로 + 미션 도메인 | `app.mission`(신규), `core.strategy(MissionType)`, `core.domain(Mission)` | M5 `ReachEvaluator` 재사용 |
| **M10** | 미션 동시성·정합성 | `app.mission`(락 전략 비교) | 낙관/비관/Redisson |
| **M11** | (선택) 미션 타입 추가 | `core.strategy` 두번째 구현 | — (MQTT 수집은 페이즈1 M-MQTT로 당겨옴) |

> **다운링크/명령 신뢰성 노트 (M9~, 로봇 확장 시 핵심).**
> 텔레메트리 업링크와 달리 명령은 유일·결과적이라 유실·중복·지연·순서가 치명적이다. 다룰 것:
> ack+재전송(at-least-once) · 명령 ID 멱등 실행 · TTL/deadline(`Mission.deadline`) · 순서 보장 ·
> 위험한 비멱등 동작은 at-most-once가 안전 · 전송은 디바이스 타입별(폰=WebSocket/푸시, 로봇=MQTT QoS) ·
> 로봇 fail-safe(하트비트/데드맨: 연결·명령 끊기면 스스로 정지).
> **엔진은 generic, 전송 어댑터는 디바이스 타입별**(양축 추상화). 페이즈 1(업링크)엔 불필요.

## 규칙
- 슬라이스 폴더(`geofence`/`mission`/`auth`/`user`)는 **해당 마일스톤에서 생성**한다(미리 빈 폴더 X).
- 마일스톤마다 `docs/measurements/Mx.md`에 before/after 수치를 남긴다.
- 한 마일스톤에 인프라 둘 이상 동시에 올리지 않는다.

## 보류된 결정 (의도적으로 지금 안 정함)
과잉결정을 피한다. 아래는 해당 마일스톤의 설계 시점에 정한다(계획서 "순진하게 먼저" 원칙).

| 주제 | 결정 시점 | 지금 정한 방향(가벼운 가드레일) |
|---|---|---|
| 인증·식별 | **실시간 후속 마일스톤(가칭 M4c) 또는 늦어도 M6** — M4 분해(M4a·M4b 완료)로 인증이 분리됨(2026-07-01), 시점 재설정 2026-07-03 | • 인증/식별은 **app 계층**(core 아님). • 보안 계층은 **공통 Principal**(디바이스·교사 둘 다 인증 주체). • 도메인은 **`Device` ≠ `User`** 분리. • 디바이스=장수명·폐기가능 토큰, 사람=단명 JWT+refresh, 즉시폐기는 Redis 블랙리스트(M6 민감성과 연결). • 세부(JWT vs opaque·토큰 수명·enrollment 모델)는 그때. |
| `Device` enrollment 필드 | **인증과 같이(가칭 M4c 또는 M6)** | 미리 넣지 않는다(투기 금지). 인증 설계 때 컬럼 추가(Flyway로 비용 ≈ 0). |
| 디바이스 **그루핑/스코핑** | **부분 확정(M4 스펙)** | **device→조직 = 1:N(`Device.orgId`) 확정** — 조직이 캐시 파티션·push 구독 키. 조직 이동 가능(드묾, 이동 명령에서 캐시 재파티션). **관리자↔조직 M:N + 권한 강제는 인증으로 보류**(super-admin 역할·`GET /api/devices` 스코프 필터 포함). "M:N 유력"은 device↔조직이 아니라 관리자↔조직 축이었음. 상세 [M4 스펙](specs/M4-realtime-read-path.md). |
| Telemetry↔Device **FK 제약** | **필요 시**(측정 근거 생길 때 — M2 시점은 지났고 무FK로 문제 없어 재보류, 2026-07-03) | 현행 무FK 유지(deviceId 문자열, 앱 upsert가 정합성 유지). 하이퍼테이블 FK 제약이 많다([0008](decisions/0008-telemetry-store-timescaledb.md) 미해결). 정합성 문제가 실측되면 FK ON/OFF 처리량을 재서 결정. 컬럼은 문자열 유지라 마이그레이션 비용 ≈ 0. |
| **fan-out 브로커 + 보존·리플레이** | 단계: M2 / M4~M5 / 페이즈2 ([ADR 0007](decisions/0007-messaging-storage-redis-streams-and-governance.md) 확정) | **최대 처리량은 싱크(배치 insert)지 큐가 아니다**(M1 측정·YAGNI §3.4) → 처리량용 브로커 도입 금지. **단계**: ① M2 = **인메모리 큐 + 배치**(외부 브로커 0). ② 두 번째 소비자(M4 푸시/M5 지오펜스) 생기면 **fan-out 브로커 = Redis Streams**(Kafka 아님 — 같은 Redis가 캐시+Streams 두 역할, 새 인프라 0). Stream=단기 버퍼(MAXLEN), 보존·리플레이는 영속 계층. ③ Kafka는 **Redis Streams의 한계가 측정으로 확인될 때** 전환(측정 게이트). • **리플레이 2종**(운영=raw/단기/전타입, 도메인=미션아카이브/장기/로봇만). • **DeviceType이 데이터 도달범위+보존을 가른다**: 폰=**장기 아카이브 경로** 구조상 없음(단기 표면의 위치 최소화는 M6). 상세 전부 ADR 0007. |
| **CD 자동화** | (마일스톤 아님 — 선택) | **측정 대상 밖**(p95 불변이라 before/after 서사 없음). M8이 컨테이너 이미지+레지스트리로 **기반만** 제공한다. 원칙: **빌드는 박스 밖**(CI/맥), **박스는 실행만**(박스 빌드는 안티패턴). 필요(배포 빈도↑) 생기면 추가: **self-hosted 러너로 배포잡만**(집 NAT 인바운드 0) + **헬스체크·자동 롤백** + **하위호환 마이그레이션**(Flyway). 측정 중엔 자동배포가 수치를 깨니 **수동/태그 트리거** 권장. |
| **텔레메트리 보존·저장소** | 저장소=**전환 완료**(M2, [ADR 0008](decisions/0008-telemetry-store-timescaledb.md)·명분 재검토 포함); 남은 보류 = raw TTL 수치(**M6**)·압축 파라미터([거버넌스 = ADR 0007](decisions/0007-messaging-storage-redis-streams-and-governance.md)) | 텔레메트리=시계열(append·불변, (device,time)범위·최신 조회). **TimescaleDB 하이퍼테이블로 전환됨**(5분 청크 + retention 12h, 압축은 단일 HDD에서 폐기 — M2-sustain). • **보존은 필수 설계**: 미성년 위치 영구보관 금지(데이터 최소화 §3.5) → raw 단기 + 시간 청크 drop으로 삭제 비용 ≈ 0. • **DeviceType이 보존·도달범위 축**(폰=장기 아카이브 경로 구조상 없음, 로봇=Mission Archive 장기) — ADR 0007. • raw TTL 수치·Mission Archive 저장소는 미해결(ADR 0007 §미해결). |
