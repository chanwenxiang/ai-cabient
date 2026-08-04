CREATE TABLE IF NOT EXISTS device_fault_report (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    device_id VARCHAR(64) NOT NULL,
    issue_type VARCHAR(32) NOT NULL,
    description VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_device_fault_report_device ON device_fault_report (device_id, created_at DESC);
