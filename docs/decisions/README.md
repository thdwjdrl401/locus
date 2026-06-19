# 아키텍처 결정 기록 (ADR)

구조를 가른 결정과 **그 이유·기각된 대안**을 남긴다. "왜 이렇게 했나"를 나중의 나와 관에게 설명하기 위한 문서.

| # | 결정 | 한 줄 요약 |
|---|---|---|
| [0001](0001-build-tool-gradle-kotlin-dsl.md) | 빌드 도구 | Gradle (Kotlin DSL) + wrapper, Java 21 toolchain |
| [0002](0002-single-module-with-archunit.md) | 모듈 구조 | 멀티모듈 기각 → **단일 모듈 + ArchUnit**으로 경계 강제 |
| [0003](0003-feature-slice-with-core-app-split.md) | 패키지 구조 | core/app 분리 + **기능별 슬라이스** (헥사고날 전면 채택 기각) |
| [0004](0004-ports-only-at-improvement-seams.md) | 포트 사용 범위 | 헥사고날 포트를 **개선 이음새에만** 외과적으로 차용 |
| [0005](0005-simulator-in-app-with-profile.md) | 시뮬레이터 위치 | 같은 앱 + `simulator` 프로파일 (별도 모듈 기각) |
| [0006](0006-package-base-name.md) | 패키지 베이스 | `com.thdwjdrl.locus` |

## 구조 ↔ 결정 매핑은 별도 문서
- 패키지 트리의 각 부분이 어느 결정·마일스톤에서 나왔는지: [../STRUCTURE.md](../STRUCTURE.md)
- 마일스톤이 트리의 어디에 떨어지는지: [../ROADMAP.md](../ROADMAP.md)
