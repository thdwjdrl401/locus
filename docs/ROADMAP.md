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
| 인증·식별 | **M4** | • 인증/식별은 **app 계층**(core 아님). • 보안 계층은 **공통 Principal**(디바이스·교사 둘 다 인증 주체). • 도메인은 **`Device` ≠ `User`** 분리. • 디바이스=장수명·폐기가능 토큰, 사람=단명 JWT+refresh, 즉시폐기는 Redis 블랙리스트(M6 민감성과 연결). • 세부(JWT vs opaque·토큰 수명·enrollment 모델)는 **M4에서**. |
| `Device` enrollment 필드 | **M4** | M0엔 넣지 않는다(투기 금지). 인증 설계 때 컬럼 추가(`ddl-auto`로 비용 ≈ 0). |
| 디바이스 **그루핑/스코핑** | **M4** | 관리자는 그룹 단위로 조회, super-admin은 역할로 전체. 모양은 `Group` 엔티티 + 멤버십(M:N 유력 — 한 디바이스를 여러 관리자/역할이 봄), `GET /api/devices`는 스코프 필터. **추가물이고 무거운 Telemetry 무관.** 정확한 모양(M:N vs 단일 FK)은 권한 규칙 정해지는 M4에 결정. |
| Telemetry↔Device **FK 제약** | **M2** | M0는 FK 없이(deviceId 문자열, 앱 upsert가 정합성 유지). 벌크 적재에서 **FK 제약 ON/OFF 처리량을 측정**해 근거로 결정. 컬럼은 문자열 유지라 마이그레이션 비용 ≈ 0. |
| **CD 자동화** | (마일스톤 아님 — 선택) | **측정 척추 밖**(p95 불변이라 before/after 서사 없음). M8이 컨테이너 이미지+레지스트리로 **재료만** 제공한다. 원칙: **빌드는 박스 밖**(CI/맥), **박스는 실행만**(박스 빌드는 안티패턴). 필요(배포 빈도↑/프로젝트) 생기면 추가: **self-hosted 러너로 배포잡만**(집 NAT 인바운드 0) + **헬스체크·자동 롤백** + **하위호환 마이그레이션**(Flyway). 측정 중엔 자동배포가 수치를 깨니 **수동/태그 트리거** 권장. |
| **텔레메트리 보존·저장소** | 보존=**M6**, 파티셔닝=**M7**; 저장소 교체는 측정 시 | 텔레메트리=시계열(append·불변, (device,time)범위·최신 조회). **MySQL 유지가 기본**(단순 우선) — 적재 병목은 fsync라 저장소를 바꿔도 안 풀림. • **보존은 필수 설계**: 미성년 위치 영구보관 금지(데이터 최소화 §3.5) → raw N일 + 오래된 건 삭제/다운샘플, recorded_at **시간 파티셔닝 drop**으로 삭제 ≈ 공짜(M7). • TSDB/컬럼스토어 전환은 "MySQL의 X 한계를 Y가 Z배 개선"이라는 **측정 근거 있을 때만**(조기 교체 금지). |
