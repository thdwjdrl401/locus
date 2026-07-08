// 함대 capacity — "고정된 N대의 디바이스가 각자 1초에 1건씩(1Hz) 보낼 때 몇 대까지 버티나"를 잰다.
//   실행: k6 run -e BASE_URL=http://박스IP:8093 load/telemetry-fleet.js
//
// MQTT 함대 모델(load/mqtt-ramp.sh)의 HTTP 대응. 두 경로가 같은 의미를 재야 교차 비교가 성립한다:
//   1 VU = 1 디바이스 = 고정 deviceId(phone-{__VU}) = 지속 keep-alive 커넥션 하나 = 1Hz 발행.
//   VU 수 N == 활성 디바이스 수 N. 디바이스 집합은 단조 증가(2000→…→10000), ID는 판 내내 고정.
//
// telemetry-capacity.js(ramping-arrival-rate)와의 차이 — 그게 재는 것과 이게 재는 게 다르다:
//   · capacity: 총 도착률(req/s)만 통제하는 열린 모델. 디바이스 ID는 회전하고 커넥션은 maxVUs로 고정.
//     "총 인입 처리량 천장"을 재기엔 맞지만, "1Hz 디바이스 몇 대"는 못 잰다(연결·per-device 주기 미통제).
//   · fleet(이 파일): VU=디바이스인 닫힌 루프. 서버가 느려지면 실제 폰처럼 디바이스가 1Hz에서 뒤처진다
//     (요청 폭주로 큐가 무한정 쌓이지 않음). 그 "뒤처짐"이 곧 함대의 한계 신호다.
//
// 판정 — knee(= 지속 가능한 최대 1Hz 디바이스 수)는 다음이 무너지기 직전 단계:
//   · device_period_ms(디바이스 실제 발행 주기)가 ~1000ms를 유의하게 넘기 시작(함대가 1Hz 못 지킴), 또는
//   · behind_schedule 카운터 급증(POST 하나가 1s 예산을 넘김 = 디바이스가 실시간에서 밀림), 또는
//   · http_reqs/s가 N을 못 따라가 정체(닫힌 루프라 offered rate 자체가 서버 능력으로 self-limit), 또는
//   · p95 급등 / 에러율↑.
//   라이브 출력의 device_period_ms·behind_schedule과 Grafana(throughput/CPU/HikariCP),
//   박스 iostat(디스크 %util)를 같이 보면 knee가 어느 자원 바운드인지 확증된다.
//
// 스윕 조절: -e DEVICES=2000,4000,6000,8000,10000  -e HOLD=120s  -e RAMP=20s
import http from "k6/http";
import { check, sleep } from "k6";
import { Trend, Counter } from "k6/metrics";

const BASE = __ENV.BASE_URL || "http://localhost:8093";
const STEPS = (__ENV.DEVICES || "2000,4000,6000,8000,10000") // 각 단계의 디바이스 수(=VU 수)
  .split(",")
  .map((s) => Number(s.trim()));
const RAMP = __ENV.RAMP || "20s"; // 다음 디바이스 수까지 올리는 시간
const HOLD = __ENV.HOLD || "120s"; // 각 디바이스 수 유지(warm-up 흡수 후 steady-state)

// 각 디바이스 수를 RAMP 램프 + HOLD 유지 → 깨끗한 plateau로 단계 관찰. 스윕은 단조 증가.
const stages = [];
for (const n of STEPS) {
  stages.push({ target: n, duration: RAMP });
  stages.push({ target: n, duration: HOLD });
}
stages.push({ target: 0, duration: "10s" });

// 디바이스의 실제 발행 주기(POST + 남은 sleep). 건강하면 ~1000ms, 밀리면 >1000ms.
const devicePeriod = new Trend("device_period_ms", true);
// POST 하나가 1s 예산을 통째로 넘겨 그 디바이스가 1Hz를 못 지킨 횟수.
const behindSchedule = new Counter("behind_schedule");

export const options = {
  discardResponseBodies: true,
  scenarios: {
    fleet_1hz: {
      executor: "ramping-vus",
      startVUs: 0,
      stages,
      // 스윕이 단조 증가라 단계 사이 VU를 죽일 필요 없음. 마지막 0으로 내릴 때만 정리.
      gracefulRampDown: "0s",
    },
  },
  thresholds: {
    // 정보용 마커(중단 안 함 — 곡선 끝까지 관찰)
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<1000"],
  },
};

// 모듈 스코프는 VU마다 독립 인스턴스 → 디바이스별 직전 발행 시각.
let lastSend = 0;

export default function () {
  const t0 = Date.now();
  if (lastSend) devicePeriod.add(t0 - lastSend);
  lastSend = t0;

  // VU=디바이스라 deviceId는 판 내내 고정. __VU는 VU 수명 동안 안정·고유.
  const deviceId = `phone-${String(__VU).padStart(5, "0")}`;
  // recorded_at = 실제 현재 시각. 1Hz 고정 디바이스라 연속 발행이 ~1000ms 간격 →
  // UNIQUE(device_id, recorded_at) ms 해상도에서 같은 ms 충돌 없음.
  const ts = new Date().toISOString();

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

  // 1Hz 페이싱 — 남은 시간만 재운다(주기 드리프트 보정).
  // 서버가 느려 elapsed>1s면 sleep 0 → 디바이스가 실시간에서 밀린다(=함대 한계 신호).
  const elapsed = Date.now() - t0;
  if (elapsed > 1000) behindSchedule.add(1);
  sleep(Math.max(0, 1 - elapsed / 1000));
}
