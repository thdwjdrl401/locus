// M4a before/after: GET /api/telemetry/latest (디바이스별 최신) 읽기 지연 측정.
//   백엔드 토글은 서버측 LATEST_SOURCE 환경변수: db(DISTINCT ON) | cache(Redis HGETALL).
//   스코프는 -e ORG: 지정 시 그 조직(?org=), 없으면 전체(super-admin).
//
// 통제 비교(권장) = 스코프 고정(조직 하나), 백엔드만 db↔cache로:
//   [box] LATEST_SOURCE=db    앱 실행 → k6 -e ORG=org-0 ...   (per-org DB)
//   [box] LATEST_SOURCE=cache 앱 실행 → k6 -e ORG=org-0 ...   (per-org 캐시)
// 데이터 규모는 시드가 정한다(load/seed-latest-dataset.sql, -v orgs=10). 스케일 바꿀 때 TRUNCATE telemetry, device.
//
// 실행:
//   k6 run -e BASE_URL=http://박스IP:8093 -e ORG=org-0 -e VUS=1  load/telemetry-latest-read.js   # 순수 지연
//   k6 run -e BASE_URL=http://박스IP:8093 -e ORG=org-0 -e VUS=20 load/telemetry-latest-read.js   # 동시성 하
//
// 닫힌 모델(VU 고정). 먼저 VUS=1로 순수 지연을 잡고, 그다음 VUS를 올려 동시성 하 열화를 본다.
import http from "k6/http";
import { check } from "k6";

const BASE = __ENV.BASE_URL || "http://localhost:8093";
const VUS = Number(__ENV.VUS || 20);
const ORG = __ENV.ORG; // 조직 스코프. 없으면 전체(super-admin).
const PATH = ORG ? `/api/telemetry/latest?org=${ORG}` : `/api/telemetry/latest`;

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
  const res = http.get(`${BASE}${PATH}`);
  check(res, { "status is 200": (r) => r.status === 200 });
}
