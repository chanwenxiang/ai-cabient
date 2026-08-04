-- 设备运维：营业锁机；异常 SLA
ALTER TABLE device_info ADD COLUMN IF NOT EXISTS sales_locked BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE ops_exception ADD COLUMN IF NOT EXISTS sla_due_at TIMESTAMPTZ;

UPDATE ops_exception
SET sla_due_at = CASE
    WHEN UPPER(COALESCE(severity, '')) IN ('CRITICAL', 'HIGH') THEN created_at + INTERVAL '4 hours'
    WHEN UPPER(COALESCE(severity, '')) = 'MEDIUM' THEN created_at + INTERVAL '24 hours'
    ELSE created_at + INTERVAL '48 hours'
END
WHERE sla_due_at IS NULL AND status IN ('OPEN', 'PROCESSING');

CREATE INDEX IF NOT EXISTS idx_ops_exception_sla_open
    ON ops_exception (status, sla_due_at)
    WHERE status IN ('OPEN', 'PROCESSING');
