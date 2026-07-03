// 1Hz 디바이스 capacity — "1초마다 텔레메트리를 보내는 디바이스 몇 대까지 버티나"를 잰다.
//   실행: k6 run -e BASE_URL=http://박스IP:8093 load/telemetry-capacity.js
//
// 왜 이 스크립트가 따로 필요한가:
//   baseline/stress는 *닫힌 모델*(VU가 응답을 받아야 다음 요청) → "동시 연결 N개"를 잰다.
//   이건 *열린 모델*(ramping-arrival-rate): 응답시간과 무관하게 **초당 N건을 도착**시킨다.
//   1Hz 디바이스 1대 = 1 req/s 이므로  target(req/s) == "1Hz 디바이스 수".
//
// 판정 — knee(= 지속 가능한 최대 1Hz 디바이스 수)는 다음이 깨지기 직전 단계:
//   · 실제 처리량(http_reqs/s)이 target을 못 따라감(plateau ≈ 박스 천장), 또는
//   · dropped_iterations > 0 (k6가 maxVUs까지 동원해도 도착률 못 맞춤 = 서버가 못 받음), 또는
//   · p95 급등 / 에러율↑.
//   라이브 출력의 VUs·dropped_iterations와 Grafana(서버 throughput/CPU/HikariCP),
//   박스 iostat(디스크 %util)를 같이 보면 knee가 디스크 바운드임이 확증된다.
//
// 단계 조절: -e RATES=10,20,30,40,50,70  -e HOLD=30s  -e DEVICES=2000
import http from "k6/http";
import { check } from "k6";
import exec from "k6/execution";

const BASE = __ENV.BASE_URL || "http://localhost:8093";
const POOL = Number(__ENV.DEVICES || 2000); // 디바이스 풀 크기
const HOLD = __ENV.HOLD || "30s"; // 각 도착률 유지 시간
const RATES = (__ENV.RATES || "10,20,30,40,50,70") // req/s = 1Hz 디바이스 수
  .split(",")
  .map((s) => Number(s.trim()));

// 각 도착률을 10s 램프 + HOLD 유지 → 깨끗한 plateau로 단계 관찰.
const stages = [];
for (const r of RATES) {
  stages.push({ target: r, duration: "10s" });
  stages.push({ target: r, duration: HOLD });
}
stages.push({ target: 0, duration: "10s" });

export const options = {
  discardResponseBodies: true,
  scenarios: {
    devices_1hz: {
      executor: "ramping-arrival-rate",
      startRate: 0,
      timeUnit: "1s", // target 단위 = 초당 요청 = 1Hz 디바이스 수
      preAllocatedVUs: Number(__ENV.PRE_VUS || 100),
      // 서버가 느려지면 in-flight VU↑ → 넉넉히(부족하면 dropped_iterations = 부하도구 한계).
      // 천장 탐색 시 MAX_VUS를 올려 부하도구가 병목이 안 되게(예: 10K 위 측정 = 6000).
      maxVUs: Number(__ENV.MAX_VUS || 2000),
      stages,
    },
  },
  thresholds: {
    // 정보용 마커(중단 안 함 — 곡선 끝까지 관찰)
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<1000"],
  },
};

export default function () {
  const i = exec.scenario.iterationInTest; // 시나리오 전역 고유 정수 (deviceId 분산용)
  const deviceId = `phone-${i % POOL}`;
  // recorded_at = 실제 현재 시각.
  // (이전 버그: base+i 가 1ms/iter로 증가 → >1000 req/s에서 실제 경과시간보다 미래로 표류 →
  //  @ValidTimestamp(미래 60s 초과)로 대량 400. ~2,300 "천장"의 정체가 이 아티팩트였음.)
  // 디바이스 2000개 라운드로빈이라 같은 device가 같은 ms에 겹칠 확률 낮고, 겹쳐도 ON CONFLICT로 dedup(202 유지).
  const ts = new Date().toISOString();

  const body = JSON.stringify({
    deviceId,
    deviceType: "PHONE",
    timestamp: ts,
    location: { lat: 37.5, lng: 127.0, accuracy: 5.0, speed: 1.0, heading: 90.0 },
    battery: { level: 80, charging: false },
    network: { type: "CELLULAR", online: true },
    activity: "WALKING",
    appState: "FOREGROUND",
    permission: "WHILE_IN_USE",
    sharingEnabled: true,
  });

  const res = http.post(`${BASE}/api/telemetry`, body, {
    headers: { "Content-Type": "application/json" },
  });
  check(res, { "status is 202": (r) => r.status === 202 });
}
