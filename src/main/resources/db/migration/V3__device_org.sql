-- M4a: 디바이스의 조직(테넌트) 소속. device→조직 = 1:N (스펙 #2 · docs/specs/M4-realtime-read-path.md).
-- 최신상태 캐시의 파티션 키이자 조회 스코프. nullable — 기존 행 호환, enrollment/권한 강제는 인증(보류).
ALTER TABLE device ADD COLUMN org_id VARCHAR(255);

-- 조직 스코프 조회(telemetry JOIN device WHERE org_id=?)와 캐시 warm-up의 device→org 조회용.
CREATE INDEX idx_device_org_id ON device (org_id);
