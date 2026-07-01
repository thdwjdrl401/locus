-- M4a before/after 측정용 정적 데이터셋 시드.
-- telemetry에 N개 디바이스 × M행(디바이스당 이력)을 벌크 적재한다.
-- 각 디바이스의 최신(= MAX recorded_at)은 r=0 (now)로 결정적.
--
-- 사용:
--   psql "$DB_PSQL_URL" -v devices=10000 -v rows=1000 -v orgs=10 -f load/seed-latest-dataset.sql
--   (DB_PSQL_URL 예: postgresql://locus:locuspw@localhost:5432/locus)
--
-- 측정 위생: 스케일을 바꿀 때마다 먼저 `TRUNCATE telemetry, device;` 로 초기화한다(런마다 통제, M2-par 교훈).
--
-- 왜 시뮬레이터(1Hz)가 아니라 벌크 적재인가:
--   1Hz로 1,000행/디바이스를 쌓으면 ~17분/런 + 라이브 적재가 읽기 측정을 오염시킨다.
--   정적 벌크 적재가 통제된 before를 준다(캐시 비용은 이력 깊이와 무관하므로 after엔 영향 없음).

-- device 행 + 조직 배정(org 파티션 측정용). org_id = 'org-' || (d mod orgs)로 균등 분할.
-- per-org 조회(DB JOIN)와 캐시 파티션(latest:{orgId})이 device.org_id를 쓴다.
INSERT INTO device (device_id, device_type, org_id)
SELECT 'phone-' || d, 'PHONE', 'org-' || (d % (:orgs)::int)
FROM generate_series(0, (:devices)::int - 1) AS d
ON CONFLICT (device_id) DO UPDATE SET org_id = EXCLUDED.org_id;

INSERT INTO telemetry (
    device_id, device_type, recorded_at, received_at,
    lat, lng, accuracy_m, altitude_m, speed_mps, heading_deg, metrics)
SELECT
    'phone-' || d,
    'PHONE',
    now() - (r || ' seconds')::interval,   -- 디바이스당 M행, 1초 간격. r=0이 최신
    now() - (r || ' seconds')::interval,
    37.5  + ((d % 100) * 0.001),            -- 결정적 위치 분산(랜덤 금지 = 재현성). 100x100 격자
    127.0 + ((d / 100) * 0.001),
    5.0, NULL, 1.0, 90.0,
    '{"battery":{"level":80,"charging":false},"network":{"type":"CELLULAR","online":true},"activity":"WALKING","appState":"FOREGROUND","permission":"WHILE_IN_USE","sharingEnabled":true}'::jsonb
FROM generate_series(0, (:devices)::int - 1) AS d,
     generate_series(0, (:rows)::int - 1)    AS r
ON CONFLICT (device_id, recorded_at) DO NOTHING;

-- 적재 후 통계 갱신(플래너가 정확한 계획을 세우도록 — 측정 공정성).
ANALYZE telemetry;
ANALYZE device;
