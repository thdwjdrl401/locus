# 협업 컨벤션 — 브랜치 · 커밋 · 태그 · 측정

트렁크 기반 협업 규칙. 솔로 작업이라도 히스토리가 읽히고 되짚을 수 있게 한다.

## 1. 브랜치 — 트렁크 기반
- `main`에 **직접 커밋**한다. 셀프 PR은 안 한다(번거로움 대비 이득 적음).
- 브랜치는 **되돌릴 위험이 있는 실험에만** 판다(예: 큰 리팩터 시도). 이름: `<type>/m<n>-<kebab-desc>` (예: `perf/m2-batch-insert`).
- `main`은 **항상 `./gradlew test` green** 을 유지한다. "느린" 코드는 OK(순진하게 먼저), "깨진" 코드는 안 됨.

## 2. 커밋 — Conventional Commits
형식: `<type>(<scope>): <제목>` + 본문 + footer. type/scope는 영어, 제목·본문은 한글.

| type | 용도 | 예 |
|---|---|---|
| `feat` | 기능 추가 | `feat(telemetry): 텔레메트리 수집 API` |
| `perf` | **성능 개선 (이 프로젝트의 핵심)** | `perf(telemetry): 단건→벌크 적재로 처리량 8.2x` |
| `fix` | 버그 수정 | `fix(device): 좌표 범위 검증 누락` |
| `refactor` | 동작 불변 구조 개선 | `refactor(core): 판정 로직을 ReachEvaluator로 분리` |
| `test` | 테스트 | `test(core): 추상화 경계 ArchUnit 규칙` |
| `docs` | 문서 | `docs: ADR 0007 인증 모델` |
| `build`/`chore`/`ci` | 빌드·설정·잡일·CI | `chore: gradle 의존성 정리` |

- **scope** = 슬라이스 이름(`telemetry`/`device`/`core`/`config`...). 생략 가능.
- `perf:` 커밋 본문엔 **헤드라인 수치 + repo 내 측정문서 경로**를 적는다.

## 3. 마일스톤 태그
- 마일스톤 완료 후 그 커밋에 태그를 단다. = **온프렘에서 측정한 코드 상태의 고정점.**
  ```
  git tag -a m2 -m "M2: Kafka 분리 + JDBC 벌크 적재 (처리량 8.2x)"
  ```
- 태그 주석엔 마일스톤 요약만. (외부 링크 안 넣음 — 아래 4 참조)

## 4. 측정 기록과 링크 방향
- **측정 원천 = repo** (`docs/measurements/Mx.md`: 수치·Grafana·해석·효과 없던 시도까지).
- **서사 = 블로그** (그래프·설명). 블로그는 **repo의 태그/커밋을 링크**한다(고정 permalink: `…/tree/m2` 또는 `…/commit/<sha>`, ❌ 브랜치 링크 금지).
- **링크는 단방향**: 블로그 → repo. **repo 안엔 블로그 링크를 넣지 않는다**(링크 로트·미완성 placeholder 방지).
- 같은 사실의 세 깊이: `git log`(스캔) → 측정문서(정밀) → 블로그(서사). 원천은 항상 repo.

## 5. 작성 예시 — M2 (단건 insert → 벌크)

**커밋:**
```
perf(telemetry): 컨슈머 단건 save를 JDBC 벌크로 전환

5,000 디바이스 유입에서 단건 insert가 적재 병목(DB CPU 95%, 컨슈머 랙 발산).
@KafkaListener 배치 + JdbcTemplate.batchUpdate(500) + rewriteBatchedStatements로 전환.

적재 처리량 1,200→9,800 rows/s (8.2x), 배치 p95 410→41ms, 랙 수렴.

Milestone: M2
Measurements: docs/measurements/M2.md
```

**측정문서**(`docs/measurements/M2.md`)에는 환경·Before표·변경내용·After표·해석·효과없던시도까지. 양식은 [measurements/README.md](measurements/README.md).

## 6. 코드 스타일
- **Lombok 안 씀.** DTO·VO·command는 **Java 21 record**(불변·간결). JPA 엔티티는 평이한 클래스 + 명시적 getter.
  - 보안 이유: Lombok `@Data`의 자동 `toString`이 위치 평문을 흘릴 수 있어, 민감 필드는 직렬화·로그를 직접 통제한다(M6).
- 포매터: **Spotless + google-java-format(AOSP, 4-space)**. CI가 `spotlessCheck`로 강제. 로컬은 `./gradlew spotlessApply`.

## 7. main green 보증
- CI(`.github/workflows/ci.yml`): push/PR마다 `spotlessCheck` + `test`(ArchUnit 포함) + gitleaks(비밀 스캔).
