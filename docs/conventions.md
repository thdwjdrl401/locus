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
- 마일스톤 완료 후 그 커밋에 태그를 단다. = **온프렘에서 측정한 코드 상태의 고정점.** 실제 예(`git tag -n`, 원문 그대로 — §7 용어 규칙은 이 태그들 뒤에 생겼고 태그는 히스토리라 고치지 않는다):
  ```
  m0       M0: 모델·수집·조회·시뮬레이터·측정 — knee≈33 req/s, HDD fsync 바운드 확증
  m2-par   M2-par: 워커 병렬화 + 적재 무손실 용량 — 단일 HDD 도착 10k rows/s 무손실(append-only), ...
  ```
- 태그 주석엔 마일스톤 요약만. (외부 링크 안 넣음 — 아래 4 참조)

## 4. 측정 기록과 링크 방향
- **측정 원천 = repo** (`docs/measurements/Mx.md`: 수치·Grafana·해석·효과 없던 시도까지).
- **서사 = 블로그** (그래프·설명). 블로그는 **repo의 태그/커밋을 링크**한다(고정 permalink: `…/tree/m2` 또는 `…/commit/<sha>`, ❌ 브랜치 링크 금지).
- **링크는 단방향**: 블로그 → repo. **repo 안엔 블로그 링크를 넣지 않는다**(링크 로트·미완성 placeholder 방지).
- 같은 사실의 세 깊이: `git log`(스캔) → 측정문서(정밀) → 블로그(서사). 원천은 항상 repo.

## 5. 작성 예시 — M1 (단건 insert → 배치, 실측)

**커밋:**
```
perf(telemetry): 요청당 단건 저장을 인메모리 큐 + 배치 적재로 전환

M0 baseline 33 req/s의 병목은 커밋마다 걸리는 fsync(요청당 1.2회).
인메모리 큐 + 배치 워커(JdbcTemplate.batchUpdate)로 N건이 fsync 1회를 공유, 내구성 유지.

fsync/req 1.2→0.059, 최대 처리량 33→1,437 req/s (~44×).

Milestone: M1
Measurements: docs/measurements/M1.md
```

**측정문서**(`docs/measurements/M1.md`)에는 환경·Before표·변경내용·After표·해석·효과없던시도까지. 양식은 [measurements/README.md](measurements/README.md).

## 6. 코드 스타일
- **Lombok 안 씀.** DTO·VO·command는 **Java 21 record**(불변·간결). JPA 엔티티는 평이한 클래스 + 명시적 getter.
  - 보안 이유: Lombok `@Data`의 자동 `toString`이 위치 평문을 흘릴 수 있어, 민감 필드는 직렬화·로그를 직접 통제한다(M6).
- 포매터: **Spotless + google-java-format(AOSP, 4-space)**. CI가 `spotlessCheck`로 강제. 로컬은 `./gradlew spotlessApply`.

## 7. 문서 작성 — 업계 표준 용어
- **임의 표현·비유를 만들지 않는다.** 개념은 그 분야에서 통용되는 표준 용어로, 문맥에 맞게 담백하게 쓴다(처음 읽는 사람도, 협업에서도 그대로 통하게). **목록에 의존하지 말고 매번 판단한다** — 기계적 치환은 오히려 어색한 표현을 만든다.
- **측정 용어 용어집** (반복되는 것만 일관성 위해 고정. 금지어 전체 목록이 아님):
  | 단어 | 이 프로젝트에서 쓰는 말 |
  |---|---|
  | 천장 / ceiling | **최대 처리량** (maximum throughput) |
  | knee (그래프 무릎) | **포화점** (saturation point) |
  | ~ 바운드 (disk-bound 등) | **~이 병목** / 병목(bottleneck) |
  | "산수로 닫힌다" 류 | **계산으로 설명된다 / 검산** |
- 영어 약어·지표(`p95`, `fsync`, `%util`, throughput)는 그대로 쓰되, **새 개념의 첫 등장 시 한글(영어) 병기**.
- 비유("배수구·양동이" 등)는 설명 대화엔 쓰더라도 **문서에는 남기지 않는다.**
- **직역체 조어를 만들지 않는다.** 영어 개념을 어색하게 직역한 신조어 대신 통용되는 한국어로 쓴다(예: living document → "상시 갱신 문서").
- **장식 이모지를 쓰지 않는다.** 의미 없는 비유 이모지(🦴 척추·🎬 조연 등)는 금지. 범례에 정의된 기능적 상태 마커(✅🔄⬜⏸🚧)만 허용한다.
- **지어낸 서사(극적 플롯)를 쓰지 않는다.** "처음엔 X였는데 알고 보니 Y" 식 반전·날조된 긴장·교훈으로 포장하지 않고, 한 일·측정·결과를 시간순으로 담담하게 쓴다.
- **의인화하지 않는다.** "측정이 말한다/요구한다", "숫자가 가리킨다"처럼 측정·데이터를 행위 주체로 쓰지 않는다. 사실 진술로 쓴다("측정으로 확정된 것은 ~까지다"). 콜론 뒤에 명령·선언을 붙이는 수사("…: 쓰기를 순차화하라")도 같은 부류 — 평서문으로 푼다.
- **자기 평가 라벨을 붙이지 않는다.** 한계·조건·트레이드오프에 "(정직 기록)" 같은 라벨을 달지 않는다. 중립적 섹션명("측정 조건·한계", "트레이드오프")으로 사실만 적는다.
- **관념어·평가어 대신 평서 표현.** "필연", "승리", "근본 결함" 대신 "가장 유리", "엔진 자체의 한계"처럼 해당 분야에서 실제 쓰는 표현으로.
- **두괄식.** 문단·절의 첫 문장이 결론, 근거는 그 뒤에.
- **라벨에 해석을 넣지 않는다.** 대시보드 패널 제목·코드/설정 식별자는 이름이지 설명이 아니다. "왜"의 해석은 측정 문서(`docs/measurements/`)에만 적는다.

## 8. main green 보증
- CI(`.github/workflows/ci.yml`): push/PR마다 `./gradlew check` = `spotlessCheck` + `test`(단위/웹·ArchUnit) + `integrationTest`(Testcontainers 실 TimescaleDB·Redis·Mosquitto) + gitleaks(비밀 스캔).
