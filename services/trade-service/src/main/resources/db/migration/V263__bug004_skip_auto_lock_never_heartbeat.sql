-- BUG-004: 从未上报心跳的设备不应因「离线超时」被自动锁售。
-- 解除历史误锁（last_heartbeat_at 为空且原因为离线超时自动停售）。

UPDATE device_info
SET sales_locked = false,
    sales_lock_reason = NULL,
    updated_at = NOW()
WHERE sales_locked = true
  AND last_heartbeat_at IS NULL
  AND sales_lock_reason LIKE '离线超时自动停售%';

-- 在线柜补写最近心跳，便于后续离线计时口径一致
UPDATE device_info
SET last_heartbeat_at = COALESCE(last_heartbeat_at, updated_at, NOW())
WHERE online_status = 'ONLINE'
  AND last_heartbeat_at IS NULL;
