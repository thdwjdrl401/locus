# 0003 — 패키지 구조: core/app 분리 + 기능별 슬라이스

- 상태: 확정
- 일자: 2026-06-19

## 결정
- 상단을 `core`(infra-free 도메인·전략·엔진)와 `app`(배선)으로 나눈다.
- `app` 내부는 **기능별 슬라이스**(`telemetry`/`device`/`geofence`/`mission`/`simulator`/`config`/`support`)로 나눈다. 레이어별(controller/service/repo)로 나누지 않는다.

## 근거
- 이 프로젝트는 M0→M11로 **기능이 계속 추가**된다. 기능별 슬라이스면 **마일스톤 ≈ 슬라이스 폴더**가 1:1로 맞는다(M5=`geofence/`, M9=`mission/`). 레이어별로 나누면 기능 하나가 세 폴더에 흩어진다.
- Spring 관례와 잘 맞고 M0를 빠르게 시작할 수 있다.

## 기각된 대안 — 헥사고날 전면 채택
헥사고날의 이득을 이 프로젝트에 대보면:
1. **도메인 격리** → `core` + ArchUnit으로 *이미* 확보(이득 중복).
2. **어댑터 교체 용이성** → 이 프로젝트는 인프라를 *교체*하는 게 아니라 *측정하며 도입*한다. 회수 안 되는 가치.
3. **포트로 변이 명시** → 진짜 변이 축은 DeviceType/MissionType이고 그건 `core`의 전략 패턴이 담당. 헥사고날 포트(인프라 방향)는 빗나간 최적화.
4. **비용**: port/in·port/out·도메인↔JPA 매퍼 보일러플레이트. 특히 계획서 M0가 `Telemetry`/`Device`를 JPA 엔티티로 정의 → 순수 헥사고날과 충돌.

결론: 이득 ①은 이미 가졌고 ②③은 빗나가고 ④ 비용만 남는다. → **전면 채택 기각, 부분 차용**([0004](0004-ports-only-at-improvement-seams.md)).

## 메모 — core의 jakarta.* 허용
`core`는 Spring/Kafka/Redis/Web에 의존하지 않지만 **JPA/Validation 표준 애너테이션(jakarta.persistence, jakarta.validation)은 허용**한다.
- 이유: 도메인 엔티티를 그대로 JPA 엔티티로 쓰면 매퍼 보일러플레이트가 사라진다. 계획서 M0의 엔티티 정의와도 맞는다.
- 트레이드오프: "완전한 infra-free"는 아니지만, 금지 대상을 *프레임워크·메시징·캐시 클라이언트*로 한정하는 게 이 규모에 실용적이다. `CoreIsolationTest`가 이 선을 코드로 못박는다.

## 영향
- `CoreIsolationTest`는 jakarta.*를 금지 목록에서 제외한다.
- 빈 슬라이스(`geofence`/`mission`)는 미리 만들지 않고 해당 마일스톤에서 생성.
