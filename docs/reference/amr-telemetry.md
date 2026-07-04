# AMR 텔레메트리 metrics 스키마 (M3 참조자료)

AMR(자율이동로봇, Autonomous Mobile Robot)이 수집 봉투 `metrics`(JSONB 자유칸)로 보내는 상태 필드다.
공통칸(`deviceId`, `deviceType`, `timestamp`, `location`)은 폰과 동일하고, 로봇 고유 상태만 여기 담는다.
좌표는 엣지/시뮬레이터가 odom→위경도로 변환해 `location`에 채우고, 원본 odom은 metrics에 보존한다.

## 스키마

| 키 | 타입 | 값 어휘 | 의미 |
|---|---|---|---|
| `batteryPercent` | integer | 0~100 | 배터리 잔량(%) |
| `batteryStatus` | string | `CHARGING` / `DISCHARGING` / `FULL` | 배터리 충전 상태 |
| `operatingMode` | string | `AUTOMATIC` / `MANUAL` / `SERVICE` | 운영 모드 |
| `driving` | boolean | — | 주행 중 여부 |
| `estopState` | string | `ESTOPPED` / `NOT_ESTOPPED` | 비상정지 상태 |
| `faultLevel` | string | `OK` / `WARN` / `FATAL` | 결함 수준 |
| `odomX` | double | m | 오도메트리 x(맵 기준) |
| `odomY` | double | m | 오도메트리 y(맵 기준) |
| `odomTheta` | double | rad | 헤딩 |
| `mapId` | string | — | 현재 맵 식별자 |

상태 필드를 core enum이 아니라 문자열 코드로 둔다 — 디바이스별 상태 어휘는 `app`에만 살고 core는 타입을 모른다(§2.2).
타입↔맵 변환은 `app.device.AmrMetrics`가 단일 소유처다(폰의 `PhoneMetrics`에 대응).

## 검증 규칙 (`app.device.AmrHandler`)

주행 중(`driving=true`)이면서 다음이면 표준 의미 모순이라 거부한다:

- `estopState=ESTOPPED` — 비상정지 상태에서 주행 불가
- `operatingMode=SERVICE` — 점검 모드에서 주행 불가
- `batteryStatus=CHARGING` — 충전 중 주행 불가

## 구조 근거

필드 구조는 개방 표준을 참조해 정의했다. 링크로만 참조하고 스키마는 이 저장소에서 독립 정의하며, 값은 시뮬레이터 합성이다.

- ROS 2 `common_interfaces`(Apache 2.0) — 로봇 표준 메시지(odometry·battery 등): https://github.com/ros2/common_interfaces
- VDA5050(MIT) — AGV/AMR 통신 표준(operatingMode·errors·battery·nodeStates 등): https://github.com/VDA5050/VDA5050

Boston Dynamics SDK는 라이선스(BDSDK-SL, 제품 전용)라 사용하지 않는다.
