// M0 baseline 부하 — POST /api/telemetry 에 VU를 단계적으로 올린다.
//   실행: k6 run load/telemetry-baseline.js
//   대상 변경: k6 run -e BASE_URL=http://온프렘:8093 load/telemetry-baseline.js
//
// M0는 "순진하게 먼저"의 기준선이다. 여기서 p95/p99·처리량·에러율을 찍어 이후 마일스톤과 비교한다.
import http from "k6/http";
import { check } from "k6";

export const options = {
  stages: [
    { duration: "30s", target: 50 }, // ramp-up
    { duration: "1m", target: 50 }, // 유지
    { duration: "30s", target: 0 }, // ramp-down
  ],
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<500"],
  },
};

const BASE = __ENV.BASE_URL || "http://localhost:8093";

export default function () {
  // VU별 고정 deviceId + 매 요청 고유 timestamp로 UNIQUE(deviceId, recorded_at) 충돌을 피한다.
  const deviceId = `phone-${String(__VU).padStart(4, "0")}`;
  const ts = new Date(Date.now() + (__ITER % 1000)).toISOString();

  const body = JSON.stringify({
    deviceId,
    deviceType: "PHONE",
    timestamp: ts,
    location: { lat: 37.5, lng: 127.0, accuracy: 5.0, speed: 1.0, heading: 90.0 },
    // M3에서 봉투 일반화로 폰 상태를 metrics로 중첩(공통칸=deviceId/deviceType/timestamp/location).
    metrics: {
      battery: { level: 80, charging: false },
      network: { type: "CELLULAR", online: true },
      activity: "WALKING",
      appState: "FOREGROUND",
      permission: "WHILE_IN_USE",
      sharingEnabled: true,
    },
  });

  const res = http.post(`${BASE}/api/telemetry`, body, {
    headers: { "Content-Type": "application/json" },
  });
  check(res, { "status is 202": (r) => r.status === 202 });
}
