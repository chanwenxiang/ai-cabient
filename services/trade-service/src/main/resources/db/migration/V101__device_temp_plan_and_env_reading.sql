-- V101: 温控计划（分时目标温度排程）+ 环境多指标读数（湿度/电压/功耗）
CREATE TABLE IF NOT EXISTS device_temp_plan (
    plan_id    BIGSERIAL PRIMARY KEY,
    device_id  VARCHAR(64) NOT NULL UNIQUE,
    enabled    BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS device_temp_plan_entry (
    entry_id      BIGSERIAL PRIMARY KEY,
    plan_id       BIGINT NOT NULL REFERENCES device_temp_plan(plan_id) ON DELETE CASCADE,
    start_minute  INT NOT NULL,
    target_temp_c INT NOT NULL,
    UNIQUE (plan_id, start_minute)
);

CREATE TABLE IF NOT EXISTS device_env_reading (
    reading_id  BIGSERIAL PRIMARY KEY,
    device_id   VARCHAR(64) NOT NULL,
    metric_type VARCHAR(16) NOT NULL,
    value       DECIMAL(10,2) NOT NULL,
    reported_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_device_env_reading_device_type_time
    ON device_env_reading (device_id, metric_type, reported_at DESC);
