# 0008 — 텔레메트리 저장소: MySQL → TimescaleDB (측정 근거 기반 순차 저장 전환)

- 상태: 확정(방향) / 측정 대기(전환 후 before/after)
- 일자: 2026-06-30
- 관련: [measurements/M1.md](../measurements/M1.md)(전환 트리거 측정), [0007](0007-messaging-storage-redis-streams-and-governance.md)(보존·거버넌스), [0003](0003-feature-slice-with-core-app-split.md)(core/app)

## 맥락 — 측정이 가리킨 전환
M1에서 적재 병목을 변수별로 분해했다(M1.md):
- 배치로 redo fsync를 풀어 포화점 33→**1,437 req/s**(내구성 유지). 그러나 디스크 %util 94·aqu-sz 2.7로 **여전히 디스크 바운드**.
- 남은 병목 = **data fsync**(InnoDB **B-tree 제자리 갱신 = 랜덤 쓰기** + doublewrite·페이지 플러시). 배치·flush로 못 줄임(A3가 증명).
- 계산: 10k req/s = ~5MB/s 순차 쓰기(HDD 대역폭의 5%). **막는 것은 대역폭이 아니라 랜덤 fsync.**

→ **쓰기를 순차화하면 같은 HDD로도 포화점이 크게 오른다.** 그 답이 LSM/시계열 저장소. ADR 0007이 "저장소 교체는 측정 근거 있을 때만"으로 보류했던 카드를, **M1이 그 측정 근거를 제공**해 연다.

## 결정
**텔레메트리 저장소를 MySQL(InnoDB B-tree)에서 TimescaleDB(PostgreSQL 확장)로 전환한다.**

후보 비교:
| | 순차쓰기 | SQL 유지 | 시계열 기능 | 비고 |
|---|---|---|---|---|
| **TimescaleDB** | ✅(하이퍼테이블 청크) | ✅(PostgreSQL) | ✅(자동 파티셔닝·보존·압축) | **채택** |
| MyRocks(MySQL+RocksDB) | ✅(LSM) | ✅(MySQL) | ✗ | 엔진만 교체, 시계열 기능 없음 |
| InfluxDB | ✅ | ✗(전용 언어) | ✅ | SQL·조인 약함 |
| Cassandra/ScyllaDB | ✅(LSM) | ✗ | △ | 분산·운영 무거움 |

TimescaleDB 선택 이유:
1. **순차 쓰기** — 하이퍼테이블이 시간 청크에 append. B-tree 제자리 갱신 제거 → data fsync 바닥 해소(전환 후 측정).
2. **SQL/PostgreSQL 유지** — 기존 쿼리·조인·트랜잭션 보존, 마이그레이션 부담 최소.
3. **시계열 기능 내장** — 시간 청크 자동 파티셔닝 + 보존정책(retention) + 압축. → **M6 보존·M7 파티셔닝을 상당 부분 흡수**(폰 데이터 단기보존 = ADR 0007 거버넌스를 retention policy로 구현).

## 트레이드오프 (정직 기록)
- **조회 패턴**: 시계열 append·범위 스캔엔 강하나, "디바이스별 최신 단건"은 캐시(Redis, M4)가 맡는 게 낫다. 역할 분담(최신=Redis, 이력=TimescaleDB).
- **운영 복잡도**: 새 인프라(PostgreSQL+확장). docker-compose 점증(§3.4).
- **마이그레이션**: 영속 계층 재작성(엔티티/레포/배치 DAO). `ddl-auto=update` 대신 스키마 관리(Flyway 검토).
- **측정 환경 한계**: 단일 HDD 박스로 풀 10k 실측은 못 미칠 수 있음. 가치는 **측정된 서사(랜덤→순차 X배)**지 절대수치 아님.

## 영향
- `core.domain.Telemetry` 매핑·`app.telemetry` 레포·`TelemetryBatchDao`(A2) 이식. core는 infra-free 유지(ArchUnit).
- ADR 0007 유지: Redis Streams(내부 fan-out)·MQTT(수집 전송)는 별개 계층, TimescaleDB(저장소)와 공존.
- 전환 후 `docs/measurements/M2.md`에 적재 포화점 before(MySQL 1,437)/after(TimescaleDB) 기록.

## 미해결
- raw 보존기간(TTL) 수치, 압축 정책 파라미터.
- 디바이스 메타(`device`)도 옮길지 vs PostgreSQL 일반 테이블로 둘지.
