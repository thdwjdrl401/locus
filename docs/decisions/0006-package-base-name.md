# 0006 — 패키지 베이스: com.thdwjdrl.locus

- 상태: 확정
- 일자: 2026-06-19

## 결정
베이스 패키지는 `com.thdwjdrl.locus`. Gradle `group`은 `com.thdwjdrl`.

## 구조
```
com.thdwjdrl.locus
├── LocusApplication        진입점 (컴포넌트 스캔 루트)
├── core.*                  infra-free 코어
├── app.*                   배선 (기능별 슬라이스)
└── (test) architecture.*   ArchUnit 경계 테스트
```
