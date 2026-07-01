# 0008 — 텔레메트리 저장소: MySQL → TimescaleDB (측정 근거 기반 순차 저장 전환)

- 상태: 확정 / 측정 대기(전환 후 before/after)
- 일자: 2026-06-30
- 관련: [measurements/M1.md](../measurements/M1.md)(전환 트리거 측정), [0007](0007-messaging-storage-redis-streams-and-governance.md)(보존·거버넌스), [0003](0003-feature-slice-with-core-app-split.md)(core/app)

## 맥락 — 측정이 가리킨 전환
M1에서 적재 병목을 변수별로 분해했다(M1.md):
- 배치로 redo fsync를 풀어 포화점 33→**1,437 req/s**(내구성 유지). 그러나 디스크 %util 94·aqu-sz 2.7로 **여전히 디스크가 병목**.
- 남은 병목 = **data fsync**(InnoDB **B-tree 제자리 갱신 = 랜덤 쓰기** + doublewrite·페이지 플러시). 배치·flush로 못 줄임(A3가 증명).
- 계산: 10k req/s = ~5MB/s 순차 쓰기(HDD 대역폭의 5%). **막는 것은 대역폭이 아니라 랜덤 fsync.**

→ **쓰기를 순차화하면 같은 HDD로도 포화점이 크게 오른다.** ADR 0007이 "저장소 교체는 측정 근거 있을 때만"으로 보류했던 것을, **M1이 그 측정 근거를 제공**해 연다.

## 결정
**텔레메트리 저장소를 MySQL(InnoDB B-tree)에서 TimescaleDB(PostgreSQL 확장)로 전환한다. 앱 전체를 PostgreSQL/TimescaleDB 단일 DB로 옮기고 MySQL은 제거한다** — Device·Mission은 일반 테이블, Telemetry만 하이퍼테이블. TimescaleDB가 곧 PostgreSQL이라 관계형 데이터는 그대로 처리되고, DB 개수는 1을 유지한다(교체이지 추가 아님).

### 결정 기준
Locus는 **관계형(Device·Mission·FK·JOIN·트랜잭션·JPA)과 시계열(고빈도 append·보존·시간범위 조회)을 한 시스템에서 함께** 요구한다. 이 둘을 **한 엔진에서 주는 것은 TimescaleDB뿐**이다. 전용 시계열 DB는 시계열만 처리하고 관계형은 별도 RDB로 분리시켜, 이중 DB 운영(데이터소스 2벌·배치 트랜잭션 분할·박스 자원 분할)을 강제한다. "측정한 랜덤 fsync를 순차 쓰기로 해소하면서 관계형을 유지"하는 조건을 만족하는 것이 선택 기준이다.

### 후보 비교
| 후보 | 순차 쓰기 | 관계형/SQL | 시계열 기능 | Locus 적합성 |
|---|---|---|---|---|
| **TimescaleDB** | ✅ 하이퍼테이블 청크 | ✅ PostgreSQL | ✅ 자동 파티셔닝·보존·압축 | **채택** — 관계형+시계열 한 엔진 |
| 순수 PostgreSQL 파티셔닝 | ✅ 시간 파티션 | ✅ | △ 수동 | 가능하나 청크 자동생성·압축·시계열 함수가 수동. TimescaleDB가 이를 제공 |
| MyRocks (MySQL+RocksDB) | ✅ LSM | ✅ MySQL | ✗ | 엔진만 교체, 시계열 기능 없음 |
| InfluxDB | ✅ | ✗ 전용 언어(Flux) | ✅ | 비관계형 → Device·Mission 별도 RDB. 디바이스 다수 = 고카디널리티 약점. 버전 격변(1→2→3) |
| ClickHouse | ✅ | △ SQL(OLAP) | ✅ | 초대용량 분석 적재엔 강하나 트랜잭션·갱신 약함. 이 규모엔 과함 |
| QuestDB | ✅ | △ SQL(PG 와이어) | ✅ | 적재 빠르나 관계형 기능·생태계·채용 노출 약함 |
| Cassandra/ScyllaDB | ✅ LSM | ✗ | △ | JOIN 없음·결과적 일관성·운영 무거움. 과함 |

## 기대 효과 (전환 후 측정으로 검증)
숫자는 약속이 아니라 M2에서 측정해 확인한다.
1. **적재 포화점 상승** — B-tree 제자리 갱신(랜덤 fsync)을 시간 청크 append(순차)로 바꿔 M1의 잔여 병목을 해소. 현 1,437 req/s에서 상승을 기대(상승폭은 측정).
2. **보존 비용 ≈ 0** — 오래된 시간 청크 drop으로 삭제. M6 보존·M7 파티셔닝을 상당 부분 흡수(폰 데이터 단기보존 = ADR 0007 거버넌스를 retention policy로 구현).
3. **시간범위·최신 이력 조회 효율** — 하이퍼테이블 시간 인덱스(읽기경로, M7).
4. **디스크 사용 감소(옵션)** — 컬럼형 압축. 적용 여부·효과는 측정.
5. **관계형 유지 + 단일 엔진** — Device·Mission 그대로, 이중 DB 회피, 운영·설정 단순.
6. **스택 정합** — PostgreSQL 요구 충족 + 시계열 저장소 경험.

## 트레이드오프
- **조회 패턴**: 시계열 append·범위 스캔엔 강하다. "디바이스별 최신"은 device당 PK 인덱스 1회(LATERAL)로 O(디바이스)에 조회된다(M4a 측정 ~35ms/1k디바이스, [measurements/M4a.md](../measurements/M4a.md)). Redis 캐시는 최신을 못 해서가 아니라 실시간 push의 스냅샷 소스로 M4b에서.
- **인프라**: MySQL→PostgreSQL 교체(추가 아님 — DB 개수 1 유지, §3.4 안 깸).
- **마이그레이션**: 영속 계층 이식(엔티티 복합 PK·배치 DAO PostgreSQL 방언·Flyway 도입). `ddl-auto=update` → `validate` + Flyway.
- **측정 해석**: MySQL과 PostgreSQL은 기본 설정(내구성·페이지 플러시 등)이 달라 엔진 1:1 벤치가 아니다. **"같은 워크로드에서 저장 구조를 랜덤→순차로 바꾼 효과"**로 해석한다. 단일 HDD 박스로 풀 10k 실측엔 못 미칠 수 있고, 가치는 측정된 변화이지 절대수치가 아니다.

## 영향
- `core.domain.Telemetry` 매핑(복합 PK `device_id`+`recorded_at`)·`app.telemetry` 레포·`TelemetryBatchDao`(A2, `ON CONFLICT`) 이식. core는 infra-free 유지(ArchUnit, jakarta.persistence만).
- Device·Mission은 PostgreSQL 일반 테이블로 이동. MySQL 의존성·컨테이너 제거.
- ADR 0007 유지: Redis Streams(내부 fan-out)·MQTT(수집 전송)는 별개 계층, TimescaleDB(저장소)와 공존.
- 전환 후 `docs/measurements/M2.md`에 적재 포화점 before(MySQL 1,437)/after(TimescaleDB) 기록.

## 미해결
- raw 보존기간(TTL) 수치, 압축 정책 파라미터(M6에서 측정·확정).
- Telemetry↔Device FK: 하이퍼테이블 FK 제약이 많아 현행 무FK(deviceId 문자열) 유지, FK ON/OFF 처리량 측정은 미룬다(ROADMAP 보류).
