# M5 — 지오펜스 판정 엔진 (스펙 노트)

도달/이탈 판정 축(`core.engine`)을 여는 마일스톤. 계획서 §4 "판정 엔진"의 첫 구현. 슬라이스1은 **얇게 + 가시화**.

## 결정 골격 (착수 전 확정 — package-info·ADR)
- 지오펜스 = **원형**(중심+반경), 판정 = **haversine**. 폴리곤은 이후(같은 인터페이스의 두 번째 구현).
- 판정 엔진은 **미션·디바이스 타입을 모름**(§2.2 양축 추상화). 지오펜스(M5)·미션 도달(M9)이 **같은 `ReachEvaluator` 공유**.
- 상태 저장은 **출력 포트**(`GeofenceStateStore`, ADR 0004) — 인메모리 → Redis(이후).
- fan-out은 텔레메트리 Stream의 **세 번째 컨슈머 그룹 `geofence`**(ADR 0007) — storage·monitoring과 독립 커서.

## 슬라이스1 구성
| 레이어 | 요소 | 비고 |
|---|---|---|
| core.strategy | `ReachEvaluator` | `isInside(Location, centerLat, centerLng, radiusM)` |
| core.engine | `RadiusEvaluator` | haversine, `Math`만(순수) |
| core.domain | `ReachTransition` | `of(Boolean prevInside, boolean now)` → ENTER/EXIT/STAY. prev=null=밖 |
| app.geofence | `StreamGeofenceConsumer` | monitoring mirror·단일 워커·poison 내성·`$`부터 |
| app.geofence | `GeofenceCatalog`/`GeofenceProperties` | config 시드 → org별 인메모리(**DB 없음**) |
| app.geofence | `GeofenceStateStore`/`InMemoryGeofenceStateStore` | per-(device,zone) inside 여부 |
| app.geofence | `GeofenceEventPublisher`/`WebSocket…` | `/topic/org/{org}/geofence` push |
| app.geofence | `GeofenceController` | `GET /api/geofences?org=` |
| 프론트 | 존 원(`L.circle`) + 이벤트 피드 + 존/마커 펄스 | 관제 화면 확장 |

## 판정 흐름 (컨슈머 1건)
`tryRead` → `location==null`이면 스킵(프라이버시 §3.5) → `DeviceOrgResolver.orgOf` → `catalog.zonesForOrg(org)` → 각 zone: `now=isInside`; `prev=store.inside`; `tr=ReachTransition.of(prev,now)`; ENTER/EXIT면 이벤트 publish + 카운터(`locus.geofence.events{type}`); `store.put`.

## 재사용
`DeviceOrgResolver`·`IngestProperties`·`tryRead` poison 패턴·`TelemetryResponse`·컨트롤러 DTO·테스트 베이스(`IntegrationTestBase`/`StreamIngestIntegrationTest`).

## 시드(데모)
`application-simulator.yml`의 `locus.geofence.seeded`(측정 기본 설정 오염 방지). AMR 순찰 사이트(37.5665/126.9780) 근처 작은 반경 존으로 실제 크로싱 발생.

## 검증
- 단위: `RadiusEvaluatorTest`·`ReachTransitionTest`(Docker 불필요).
- 통합(CI): `StreamGeofenceIntegrationTest` — 존 안팎 POST → ENTER/EXIT 카운터.
- ArchUnit: `CoreIsolationTest` green(core 순수).
- 박스 수동: 시뮬(stream) → `/?org=org-0` 존 원·이벤트 피드·펄스.

## 이후 슬라이스 (범위 밖)
CRUD API·DB 영속(Flyway)·폴리곤·`GeofenceStateStore` Redis 구현·판정 처리량 측정(geofence CG 10k lag 유계).
