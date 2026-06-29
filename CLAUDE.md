# CLAUDE.md — Locus 프로젝트 작업 규칙

이 파일은 이 저장소에서 작업하는 모든 에이전트/사람이 따르는 규칙이다.
**여기 적힌 규칙은 기본 동작을 덮어쓴다.** 결정의 *이유*는 `docs/decisions/`(ADR)에 있고,
*구조 매핑*은 `docs/STRUCTURE.md`, *마일스톤 위치*는 `docs/ROADMAP.md`에 있다.

---

## 0. 프로젝트 한 줄 요약
실시간 디바이스 위치·상태 추적 플랫폼. 디바이스를 추상화해 폰(`PHONE`)은 구체 타입일 뿐이며 코어는 로봇·드론·태그에도 적용된다.
엔지니어링 원칙: **가장 단순한 정답부터(YAGNI) → 부하·프로파일링으로 실제 병목 발견 → 측정 근거로 개선.**

## 1. 스택 / 빌드
- Java 21, Spring Boot 3.4, **Gradle (Kotlin DSL)**. 빌드는 항상 `./gradlew`(wrapper, Gradle 8.11.1).
- Java는 toolchain으로 **21 고정**. 다른 JVM에서 돌려도 컴파일 타깃은 21.
- DB는 MySQL 8. 인프라는 `docker-compose`로 기동하되 **앱은 M8 전까지 호스트 JVM에서 직접 실행**한다(GC·힙 측정을 깨끗하게).

## 2. 절대 깨면 안 되는 구조 규칙

### 2.1 core는 infra-free (ADR 0002·0003)
- `com.thdwjdrl.locus.core`는 **Spring / Kafka / Redis 클라이언트 / Web(servlet)** 에 의존하지 못한다.
- 이건 `src/test/.../architecture/CoreIsolationTest.java`(ArchUnit)가 빌드 단에서 강제한다. **이 테스트를 우회·약화하지 말 것.**
- 예외: **JPA/Validation 표준 애너테이션(jakarta.persistence, jakarta.validation)은 허용**(매퍼 보일러플레이트 회피, ADR 0003 메모). 금지 대상은 프레임워크·메시징·캐시뿐.
- core가 비어서 ArchUnit이 "검사할 클래스 없음"으로 실패하는 동안만 `allowEmptyShould(true)`를 둔다. **M0에서 core에 도메인 클래스가 생기면 규칙이 실효를 갖는다.**

### 2.2 양축 추상화 (계획서 §3·§4)
- **인터페이스는 `core.strategy`, 구현은 `app`**. (DeviceTypeHandler/MovementProfile/MissionType/ReachEvaluator)
- 판정 엔진(`core.engine`)은 미션·디바이스 타입을 몰라야 한다. 의존성 방향은 항상 안쪽(core)으로.
- **검증 지점**: 새 디바이스 타입(M3)·미션 타입(M9) 추가 시 `core`의 `git diff`가 **0줄**이어야 한다. PR에서 이를 확인한다.

### 2.3 패키지: 기능별 슬라이스 (ADR 0003)
- `app`은 레이어별이 아니라 **도메인 기능별 슬라이스**(`telemetry`/`device`/`simulator`/`config`/`support` ...). 마일스톤 ≈ 슬라이스.
- 횡단 관심사만 `app.support`. 애매하면 해당 슬라이스 안에 둔다.

### 2.4 포트는 개선 이음새에만 (ADR 0004)
- 헥사고날을 전면 채택하지 않는다. 출력 포트(interface)는 **"구현을 갈아끼운다고 계획서가 명시한 이음새"에만** 둔다: M2 수집, M4 캐시, M5 지오펜스 상태저장.
- 그 외(controller→service→repo)는 슬라이스 안에서 평범하게 직접 호출. 모든 인프라를 포트로 감싸지 말 것.

### 2.5 코드 스타일 (docs/conventions.md §6)
- **Lombok 안 씀.** DTO·VO·command는 **Java 21 record**(불변·간결), JPA 엔티티는 평이한 클래스 + 명시적 getter.
- 민감 필드(위치 등)는 `toString`/직렬화/로그를 직접 통제한다. 자동 `toString`이 평문을 흘리지 않게(M6).
- 포매터 **Spotless + google-java-format(AOSP, 4-space)**. 커밋 전 `./gradlew spotlessApply`, CI가 `spotlessCheck`로 강제.

### 2.6 문서는 업계 표준 용어로 (docs/conventions.md §7)
- **임의 표현·비유를 만들지 않는다.** 개념은 그 분야에서 통용되는 표준 용어로 쓴다(·협업에서 그대로 통하게). 예: 천장→**최대 처리량**, knee→**포화점**, ~바운드→**~이 병목**.
- 새 개념 첫 등장 시 **한글(영어) 병기**. 비유는 설명 대화엔 쓰되 **문서엔 남기지 않는다.**

## 3. 일하는 방식 (계획서 철학)

### 3.1 측정으로 증명한다 (계획서 §4·§9)
- **숫자 없으면 개선이 아니다.** 성능/구조 변경은 `docs/measurements/Mx.md`에 **before → 변경 → after → 해석**을 남긴다.
- 추측("느려질 것 같다")이 아니라 측정("재현해보니 X 병목 → Y로 개선 → Z배")에 근거해 판단한다. 효과 없던 시도도 솔직히 기록한다.

### 3.2 단순하게 먼저
- 처음엔 단건 insert·offset 페이징·새로고침 조회 같은 **가장 단순한 정답**으로 둔다(조기 최적화 금지).
- 부하·스트레스 테스트로 실제 한계를 재현하고, **측정 근거가 가리키는 곳만** 최적화한다. 그 측정 기록이 변경의 정당화다.

### 3.3 과잉결정 금지 — 해당 마일스톤까지 미룬다
- 지금 안 정해도 M0가 안 막히고 되돌리기가 싸면 **미룬다.** "마지막 책임 시점"에 정한다.
- 미루는 결정은 `docs/ROADMAP.md`의 "보류된 결정" 표에 *결정 시점 + 가벼운 가드레일*만 적는다.
- 현재 보류 중(요약): **인증·식별 = M4**. 방향만 — 인증/식별은 app 계층(core 아님), 보안 계층은 공통 `Principal`, 도메인은 `Device` ≠ `User`. 세부(JWT vs opaque·토큰 수명·enrollment)는 M4에서. **M0의 Device에 인증 필드 미리 넣지 말 것.**

### 3.4 인프라는 하나씩 (계획서 §11)
- 한 마일스톤에 인프라 둘 이상 동시에 올리지 않는다. `docker-compose.yml`은 마일스톤별로 점증(M0=mysql, M2=kafka, M4=redis).
- 빈 슬라이스 폴더(`geofence`/`mission`/`auth`/`user`)를 미리 만들지 않는다. 해당 마일스톤에서 생성.

### 3.5 민감정보를 1급 시민으로 (계획서 §3·M6)
- 미성년자 위치는 가장 민감한 개인정보다. `permission=DENIED`·`sharingEnabled=false`면 위치 미수집(최소 수집).
- 복호화 데이터가 로그·힙 덤프에 평문으로 남지 않게 한다(M6). 로그에 위치 평문 출력 금지.

## 4. 검증 / 완료 기준
- 변경 후 `./gradlew test`가 green이어야 한다(ArchUnit 경계 포함).
- 마일스톤을 넘기기 전, 그 마일스톤의 검증 항목이 테스트로 통과하는지 확인한다.
- 완료를 주장하기 전 증거(테스트 결과·측정 수치)를 제시한다. 추측으로 "됐다"고 하지 않는다.

## 5. Git / 협업 컨벤션 (docs/conventions.md)
- **트렁크 기반**: `main`에 직접 커밋, PR 없음. 위험한 실험만 브랜치(`<type>/m<n>-<desc>`).
- **Conventional Commits**: `<type>(<scope>): 제목`. `perf:`엔 헤드라인 수치 + repo 내 측정문서 경로.
- **마일스톤 태그** `m0`,`m1`… = 온프렘 측정 코드 상태의 고정점. 태그 주석에 외부 링크 안 넣음.
- **링크 단방향**: 블로그 → repo(태그/커밋 permalink). **repo 안엔 블로그 링크를 넣지 않는다.**
- `main`은 항상 `./gradlew spotlessCheck test` green (CI가 강제).
- 커밋·푸시는 **명시적으로 요청받았을 때만** 한다.
- 힙 덤프(`*.hprof`)·GC 로그·비밀 파일은 `.gitignore` 대상. 비밀은 절대 커밋 금지(SECURITY.md).

## 6. 빠른 참조
| 무엇 | 어디 |
|---|---|
| **진행 현황 체크리스트(단일 진실원)** | `docs/STATUS.md` |
| 결정 이유(ADR) | `docs/decisions/` |
| 트리 ↔ 결정 매핑 | `docs/STRUCTURE.md` |
| 마일스톤 ↔ 위치 + 보류 결정 | `docs/ROADMAP.md` |
| 측정 양식·기록 | `docs/measurements/` |
| 브랜치·커밋·태그·측정 컨벤션 | `docs/conventions.md` |
| 보안 정책 | `SECURITY.md` |
| 경계 강제 테스트 | `src/test/.../architecture/CoreIsolationTest.java` |

## 7. 작업 추적 — STATUS.md를 항상 최신으로 (하네스)
**모든 작업·결정은 `docs/STATUS.md`에 반영한다.** 이게 진행의 **단일 진실원**이다. 이 규칙은 기본동작을 덮어쓴다 — 빠뜨리지 말 것.

- **작업 완료 시**: 해당 체크박스를 `[x]`로, 상태 마커(✅/🔄/⬜)를 갱신. 없던 작업이면 항목을 추가하고 체크.
- **착수 시**: 시작하는 항목을 🔄로, **"🎯 현재 포커스"** 를 지금 상태로 갱신.
- **결정 시**: 되돌리기 싼 **보류 결정** → `docs/ROADMAP.md` "보류된 결정" 표 + STATUS에 흔적. **확정 결정** → 해당 문서(ADR/STRUCTURE/conventions) + STATUS 반영. 결정은 *결정 시점 + 가벼운 가드레일*만(과잉결정 금지, §3.3).
- **커밋과 동기화**: 기능·측정·결정을 담은 커밋은 **같은 커밋에 STATUS 갱신을 포함**한다. "코드는 바뀌었는데 STATUS는 그대로"를 만들지 않는다.
- **측정**: STATUS엔 체크/한 줄 결과만, 상세 수치·해석은 `docs/measurements/Mx.md`.
- **세션 시작 시**: STATUS의 "현재 포커스"부터 읽고 거기서 이어간다.
