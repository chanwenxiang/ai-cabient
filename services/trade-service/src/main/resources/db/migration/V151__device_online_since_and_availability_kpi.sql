-- 设备最近一次恢复在线的时间戳（离线时置空），用于“稳定在线自动解锁”判断
ALTER TABLE device_info ADD COLUMN IF NOT EXISTS online_since TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_device_info_online_since
    ON device_info (online_status, sales_locked, online_since);

-- 存量在线设备回填：以最近心跳时间为准
UPDATE device_info SET online_since = updated_at
WHERE online_status = 'ONLINE' AND online_since IS NULL;

-- 设备可用性 KPI 日快照（锁机数 / 恢复时长 / 人工介入率）
CREATE TABLE IF NOT EXISTS device_availability_kpi_daily (
    kpi_date                 DATE PRIMARY KEY,
    device_total             INT NOT NULL DEFAULT 0,
    offline_events           INT NOT NULL DEFAULT 0,
    auto_lock_count          INT NOT NULL DEFAULT 0,
    auto_unlock_count        INT NOT NULL DEFAULT 0,
    manual_unlock_count      INT NOT NULL DEFAULT 0,
    avg_lock_hours           DOUBLE PRECISION,
    avg_recover_hours        DOUBLE PRECISION,
    manual_intervention_rate DOUBLE PRECISION,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE device_availability_kpi_daily IS '设备可用性 KPI 日快照';
COMMENT ON COLUMN device_info.online_since IS '设备最近一次恢复在线的时间（离线时置空）';
