// capacity 탐색 — VU를 한계까지 단계적으로 올려 "무너지는 지점(knee)"을 찾는다.
//   실행: k6 run -e BASE_URL=http://박스IP:8093 load/telemetry-stress.js
// p95가 급증하거나 에러율이 오르거나 처리량이 정체되는 단계가 타깃 박스의 한계.
import http from "k6/http";
import { check } from "k6";

export const options = {
  stages: [
    { duration: "1m", target: 50 },
    { duration: "1m", target: 100 },
    { duration: "1m", target: 200 },
    { duration: "1m", target: 400 },
    { duration: "1m", target: 800 },
    { duration: "30s", target: 0 },
  ],
  // 임계 초과 시 표시만(중단 안 함) — knee를 끝까지 관찰
  thresholds: {
    http_req_failed: ["rate<0.05"],
    http_req_duration: ["p(95)<1000"],
  },
};

const BASE = __ENV.BASE_URL || "http://localhost:8093";

export default function () {
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
