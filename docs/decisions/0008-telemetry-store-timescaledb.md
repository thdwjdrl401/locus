# 0008 — 텔레메트리 저장소: MySQL → TimescaleDB 전환

- 상태: 전환 완료(효과 실측) / MySQL 잔류 대안은 미검증(재검토 2026-07-03)
- 일자: 2026-06-30 (재작성 2026-07-03)
- 관련: [measurements/M1.md](../measurements/M1.md)(트리거), [M2.md](../measurements/M2.md)·[M2-par.md](../measurements/M2-par.md)·[M2-sustain.md](../measurements/M2-sustain.md)(검증), [0007](0007-messaging-storage-redis-streams-and-governance.md)(보존·거버넌스), [0003](0003-feature-slice-with-core-app-split.md)(core/app)

## 결정
텔레메트리 저장소를 MySQL(InnoDB)에서 TimescaleDB(PostgreSQL 확장)로 전환했다. 앱 전체를 단일 PostgreSQL로 옮기고 MySQL을 제거했다 — Telemetry만 하이퍼테이블, Device·Mission은 일반 테이블. DB 개수는 1 유지(교체이지 추가 아님).

요약: M1이 특정한 병목은 랜덤 쓰기다. 해소 경로는 여러 개였고, 더 작은 수정(telemetry PK 재설계 + 시간 파티셔닝)을 재보지 않고 전체 이관을 택했다. 전환의 효과는 이후 측정으로 확인됐고, MySQL 잔류로도 같은 결과가 가능했는지는 확인되지 않았다.

## M1이 확정한 것 (전환 트리거)
- 배치로 커밋당 fsync를 풀어 33→1,437 req/s. 그러나 디스크 %util 94·aqu-sz 2.7로 여전히 디스크가 병목. 배치·flush 설정으로는 더 줄지 않았다(A3).
- 원인: telemetry PK `(device_id, recorded_at)`가 clustered index라, 같은 시각에 여러 device가 들어오면 행이 B-tree 곳곳에 나뉘어 기록된다(+ doublewrite) = 랜덤 위치 쓰기. 엔진 자체의 한계라기보다, 읽기에 맞춘 복합 PK를 clustered로 쓴 선택의 결과다.
- 검산: 10k req/s ≈ 5MB/s로 HDD 순차 대역폭의 5%. 병목은 대역폭이 아니라 랜덤 쓰기다.
- 이 측정으로 확정된 것은 "병목 = 랜덤 쓰기"까지다. 저장소를 교체해야 한다는 결론은 이 측정만으로는 나오지 않는다.

## 전환이 바꾼 것과 검증
효과는 두 단계로 나왔다. 시점을 구분해 적는다.
- **M2(전환 직후, 기본 7일 청크)**: 행이 heap 끝에 append(순차)로 쌓여, clustered 삽입이 나뉘어 기록되던 문제가 없어진다. 실측 1,437→5,459 rows/s(~3.8×, 단일 워커), 디스크 평균 쓰기 200KB 순차·피크 util 59%(포화에서 벗어남). 이 시점의 런은 매번 테이블을 비워 인덱스가 작았다.
- **M2-sustain(지속 적재)**: 시간이 지나면 인덱스 랜덤 삽입이 문제로 돌아온다 — 청크가 커져 활성 인덱스가 shared_buffers를 넘으면 insert마다 디스크 읽기가 생겨 처리량이 도착률 밑으로 떨어진다. 청크를 5분으로 줄여 활성 인덱스를 메모리에 유지(+ retention 12h). 워커 병렬(M2-par)과 합쳐 지속 10k 무손실(63분 편차 ±1%, insert 중 디스크 읽기 ≈ 0).
- 단 3.8×는 엔진 1:1 벤치가 아니다 — 기본 설정(내구성·플러시)과 스키마 배치가 같이 바뀌었다. "같은 워크로드에서 저장 구조를 랜덤→순차로 바꾼 효과"로 읽는다.

## 검증하지 않은 대안 (MySQL 잔류 경로)
- **PK 재설계 + 시간 파티셔닝**: auto-increment 선행 PK(행이 뒤에 순차로 붙음) + `(device_id, recorded_at)` 유니크 보조 인덱스 + 시간 파티셔닝(활성 파티션 인덱스를 메모리 크기로 유지 — PG의 작은 청크와 같은 원리, DROP PARTITION으로 보존) + 워커 병렬. 재보지 않았다. 제약 둘 — ① 중복 방지용 `(device_id, recorded_at)`는 유니크여야 하는데 **InnoDB change buffer는 유니크 보조 인덱스의 insert를 버퍼링하지 못한다.** 이 인덱스의 랜덤 삽입을 흡수하는 것은 change buffer가 아니라 활성 파티션 소형화라, 파티셔닝 병행이 사실상 필수다(PK 재설계 단독으로는 부족했을 수 있음). ② MySQL 파티션 테이블은 모든 유니크 키가 파티션 컬럼을 포함해야 해 PK는 `(id, recorded_at)` 형태가 된다(id 선행이라 삽입은 여전히 순차). doublewrite 잔여 비용 포함, 실제 효과는 측정해야 안다.
- **MyRocks(LSM)**: HDD 쓰기 순차화는 구조상 가장 유리하다(compaction도 순차 위주). 선택하지 않은 이유 — telemetry(MyRocks)+device(InnoDB)가 한 트랜잭션이면 엔진을 걸쳐 원자성이 불명확, 읽기 증폭(디바이스별 최신 조회에 불리), 운영 사례가 적다. 역시 미측정.
- 엔진 일반론도 같은 방향이다: HDD 쓰기 순차화 능력은 LSM ≳ InnoDB(순차 PK) ≳ PostgreSQL ≳ InnoDB(랜덤 PK). PostgreSQL heap은 행 삽입만 순차고 인덱스 랜덤 쓰기·Full Page Writes가 남아, 일반적으로 HDD에 유리한 편이 아니다. 이 박스에서 효과가 난 것은 엔진 교체 자체보다 구성(작은 청크 + 배치 insert)이 랜덤 쓰기를 메모리로 옮겼기 때문이다. Full Page Writes는 쓰기 양을 늘리지만 WAL은 순차라, 대역폭이 남고 랜덤 I/O 비용이 큰 이 워크로드에서는 실측에서 문제가 되지 않았다.

## 선택 근거
- **워크로드에 맞춘 도구 정렬**: telemetry는 raw 대량 append + 시간 단위 통째 폐기다. 순차 append + 파티션 통째 drop(행별 DELETE 대비 비용 ≈ 0)이 이 워크로드에 맞고, 이 구조는 특정 DB 고유가 아니다(InnoDB 재스키마로도, PG로도 가능).
- **운영 자동화**: 청크 자동 생성 + retention 정책(MySQL 수동 파티션 대비 관리 작업이 적다). 실사용 TimescaleDB 기능은 이 둘이다 — 압축은 단일 HDD에서 폐기(M2-sustain), 연속집계는 미구현. 이 둘은 순수 PG 파티셔닝으로도 수동으로는 된다.
- 관계형 유지 + 이중 DB 회피, 엔진을 걸친 트랜잭션 회피, PostgreSQL/시계열 스택 경험.

## 후보 비교
| 후보 | 순차 쓰기 | 관계형/SQL | 시계열 기능 | 메모 |
|---|---|---|---|---|
| **TimescaleDB** | △ heap append(인덱스 랜덤·FPW 잔존, 작은 청크가 흡수) | ✅ PostgreSQL | ✅ 자동 청크·retention(압축·연속집계 미사용) | **채택** — 워크로드 정렬 + 운영 자동화 |
| InnoDB + PK 재설계 + 파티셔닝 | ✅ auto-inc 선행이면 순차 append(유니크 보조 인덱스는 파티션 소형화로 흡수) | ✅ MySQL | △ 파티셔닝 수동 | 전체 이관보다 작은 수정. 미측정(§미해결) |
| MyRocks (MySQL+RocksDB) | ✅ LSM(HDD 쓰기에 가장 유리) | ✅ MySQL | ✗ | 엔진 걸친 tx·읽기 증폭·운영 사례 적음. 미측정 |
| 순수 PostgreSQL 파티셔닝 | △ 위와 같음 | ✅ | △ 수동 | 실사용 기능은 이걸로도 됨. TimescaleDB는 그걸 자동화 |
| InfluxDB | ✅ | ✗ 전용 언어(Flux) | ✅ | 비관계형 → Device·Mission 별도 RDB. 고카디널리티 약점 |
| ClickHouse | ✅ | △ SQL(OLAP) | ✅ | 트랜잭션·갱신 약함. 이 규모엔 과함 |
| QuestDB | ✅ | △ SQL(PG 와이어) | ✅ | 관계형 기능·생태계 약함 |
| Cassandra/ScyllaDB | ✅ LSM | ✗ | △ | JOIN 없음·결과적 일관성·운영 무거움. 과함 |

## 트레이드오프
- **조회**: 디바이스별 최신은 device당 인덱스 1회(LATERAL)로 O(디바이스) — M4a 실측 35ms/1k디바이스([measurements/M4a.md](../measurements/M4a.md)). 시간범위 스캔은 하이퍼테이블에 유리.
- **마이그레이션 비용**: 영속 계층 이식(복합 PK·배치 DAO PostgreSQL 방언·Flyway 도입), `ddl-auto=update` → `validate`.
- **인프라**: 교체이지 추가 아님(DB 개수 1 유지, §3.4 안 깸).

## 영향
- `core.domain.Telemetry`(복합 PK)·`TelemetryBatchDao`(`ON CONFLICT`) 이식. core는 프레임워크 런타임-free 유지(ArchUnit).
- Device·Mission은 PostgreSQL 일반 테이블로 이동. MySQL 의존성·컨테이너 제거.
- ADR 0007 유지: Redis Streams(내부 fan-out)·MQTT(수집 전송)는 별개 계층, TimescaleDB(저장소)와 공존.
- before/after 수치는 [measurements/M2.md](../measurements/M2.md).

## 미해결
- **telemetry PK 재설계(+시간 파티셔닝) vs 처리량 실험** — 병목이 엔진이었는지 PK 선택이었는지를 수치로 닫는다.
- raw 보존기간(TTL) 수치·압축 파라미터(M6).
- Telemetry↔Device FK: 현행 무FK 유지, 필요 시 측정(ROADMAP 보류표).
