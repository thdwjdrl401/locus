# 측정 기록

핵심 규칙: **숫자 없으면 개선이 아니다.**
마일스톤마다 `Mx.md`에 **변경 전 수치 → 변경 내용 → 변경 후 수치 → 해석**을 남긴다.
각 마일스톤의 "해석"은 객관적 측정 리포트다. 효과 없던 시도도 솔직히 기록한다.
마일스톤을 가로지르는 병목 이동 서사(M0→M2-sustain→M4a)는 [../PERFORMANCE.md](../PERFORMANCE.md)에 있다. 이 폴더는 단계별 상세, 그쪽은 횡단 요약이다.

## 양식 (각 Mx.md)

```markdown
# Mx — <제목>

## 환경
- 하드웨어 / JVM 옵션 / 동시 디바이스 수 / 부하 도구(k6 시나리오)

## 부하로 드러난 한계 (해당 시)
- 단순 구현이 어디서·왜 한계에 부딪혔나 (부하/스트레스 테스트로 재현)

## Before
- p95 / p99 / 처리량(req/s, rows/s) / 에러율 / 기타 지표
- (Grafana 스크린샷 첨부)

## 변경 내용
- 무엇을 어떻게 바꿨나

## After
- 같은 지표 재측정

## 해석
- 왜 좋아졌나 / 남은 한계 / 다음 병목
```

## 파일 (측정 순)
- [M0.md](M0.md) — baseline: 단건 insert 33 req/s, 병목 = HDD fsync
- [M1.md](M1.md) — 배치 적재 33 → 1,437 req/s(~44×), 내구성 유지
- [M2.md](M2.md) — TimescaleDB 전환: 랜덤 → 순차 쓰기, 5,459 rows/s(~3.8×)
- [M2-par.md](M2-par.md) — 배치 워커 병렬화: 단일 HDD 도착 10k 무손실
- [M2-sustain.md](M2-sustain.md) — 5분 청크 + retention 12h: 지속 10k 63분 평평, 압축은 측정으로 기각
- [M4a.md](M4a.md) — 읽기 경로 쿼리 재설계: 관제 조회 p95 8.65s → 35ms(~250×)
- [M4b.md](M4b.md) — Redis Streams fan-out: 지속 10k 무손실·재시작 at-least-once
- [M-MQTT.md](M-MQTT.md) — MQTT 수집 경로: 인입 병렬화 3.25K → ~9K
- [M-http-capacity.md](M-http-capacity.md) — HTTP 인입 최대 처리량의 병목 = 박스 CPU(12K → 16K 선형 확장)
- [disk-baseline.md](disk-baseline.md) — fio HDD 특성화(fsync·seek 지연, regime 구분)
- [M-e2e-soak.md](M-e2e-soak.md) — 전 구간 통합 소크: 60분+ 무손실, 지속 한계 = 부하 생성기
- [RUNBOOK.md](RUNBOOK.md) — 2-머신 측정 절차
