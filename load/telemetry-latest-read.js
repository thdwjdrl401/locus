// M4a before/after: GET /api/telemetry/latest (디바이스별 최신) 읽기 지연 측정.
//   before = DB 상관 서브쿼리(findLatestPerDevice), after = Redis HGETALL.
//
// 디바이스 스케일은 시드된 데이터셋이 정한다(load/seed-latest-dataset.sql):
//   TRUNCATE telemetry;  → seed devices=1000  → 이 스크립트 → 기록
//   TRUNCATE telemetry;  → seed devices=5000  → 이 스크립트 → 기록
//   TRUNCATE telemetry;  → seed devices=10000 → 이 스크립트 → 기록
//
// 실행:
//   k6 run -e BASE_URL=http://박스IP:8093 -e VUS=1  load/telemetry-latest-read.js   # 깨끗한 per-query 지연
//   k6 run -e BASE_URL=http://박스IP:8093 -e VUS=20 load/telemetry-latest-read.js   # 동시 조회 하 degrade
//
// 닫힌 모델(VU 고정). 헤드라인 = 디바이스 수별 p50/p95/p99 곡선(before는 오르고 after는 평평).
// 먼저 VUS=1로 순수 쿼리 지연을 잡고, 그다음 VUS를 올려 동시성 하 열화를 본다.
import http from "k6/http";
import { check } from "k6";

const BASE = __ENV.BASE_URL || "http://localhost:8093";
const VUS = Number(__ENV.VUS || 20);

export const options = {
  stages: [
    { duration: "20s", target: VUS }, // ramp-up (JIT·버퍼풀 워밍 → 버림)
    { duration: "40s", target: VUS }, // steady-state (해석 구간)
    { duration: "10s", target: 0 }, // ramp-down
  ],
  thresholds: {
    http_req_failed: ["rate<0.01"],
    // 정보용 마커(중단 안 함 — before가 느린 걸 관찰하는 게 목적)
    http_req_duration: ["p(95)<2000"],
  },
};

export default function () {
  const res = http.get(`${BASE}/api/telemetry/latest`);
  check(res, { "status is 200": (r) => r.status === 200 });
}
