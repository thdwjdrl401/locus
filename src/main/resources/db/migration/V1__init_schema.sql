-- M2: TimescaleDB 초기 스키마 (ADR 0008)
-- ddl-auto=validate 전제: Hibernate가 기대하는 컬럼 타입과 정확히 일치해야 한다.
-- Instant→timestamptz, String→varchar(255), Double→double precision, enum(STRING)→varchar(255)

CREATE EXTENSION IF NOT EXISTS timescaledb;

-- 디바이스 메타 (일반 PostgreSQL 테이블)
CREATE TABLE device (
    id            BIGSERIAL PRIMARY KEY,
    device_id     VARCHAR(255) NOT NULL,
    device_type   VARCHAR(255) NOT NULL,
    status        VARCHAR(255) NOT NULL DEFAULT 'UNKNOWN',
    first_seen_at TIMESTAMPTZ,
    last_seen_at  TIMESTAMPTZ,
    metadata      JSONB,
    CONSTRAINT uk_device_device_id UNIQUE (device_id)
);

-- 텔레메트리 — 복합 PK (device_id, recorded_at)
-- 하이퍼테이블 제약: 모든 unique/PK 인덱스에 파티션 컬럼(recorded_at) 포함 필수 → 복합 PK가 충족
CREATE TABLE telemetry (
    device_id   VARCHAR(255) NOT NULL,
    device_type VARCHAR(255) NOT NULL,
    recorded_at TIMESTAMPTZ  NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL,
    lat         DOUBLE PRECISION,
    lng         DOUBLE PRECISION,
    accuracy_m  DOUBLE PRECISION,
    altitude_m  DOUBLE PRECISION,
    speed_mps   DOUBLE PRECISION,
    heading_deg DOUBLE PRECISION,
    metrics     JSONB,
    PRIMARY KEY (device_id, recorded_at)
);

-- telemetry를 하이퍼테이블로 전환 (recorded_at 기준 시간 파티셔닝)
SELECT create_hypertable('telemetry', by_range('recorded_at'));

-- 미션 (M9에서 구현 예정; 현재는 스키마만 정의)
CREATE TABLE mission (
    id        BIGSERIAL PRIMARY KEY,
    type      VARCHAR(255) NOT NULL,
    params    JSONB,
    device_id VARCHAR(255),
    status    VARCHAR(255),
    deadline  TIMESTAMPTZ
);
