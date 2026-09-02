-- UAT: demo cabinet CAB-001 may be auto sales-locked after offline timeout in dev.
-- Idempotent unlock so consumer H5 open-door / session-restore tests can proceed.

UPDATE device_info
SET sales_locked = false,
    sales_lock_reason = NULL,
    updated_at = NOW()
WHERE device_id = 'CAB-001'
  AND sales_locked = true;
