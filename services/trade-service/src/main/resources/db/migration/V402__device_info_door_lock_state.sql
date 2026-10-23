-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- TABLES: device_info (~十行)
-- COMPETITOR_REF: CB-065
-- 门磁实时状态落库：V16 §4.3.2.12 getDoorState 下发后，设备经 HTTP §4.2.16
-- deviceInfo/getDoorInfo 回 {doorState:open|close, lockState:open|close}。
-- 此前该端点只写审计日志，运营看不到结果；柜门被采集会话解锁后无法回锁，
-- 「锁没锁」需要设备侧证据（§4.2.16 全史 0 命中正是因为从没下发过触发指令）。
-- NOTES: 三列可空、无默认值，ADD COLUMN 走 metadata-only，不重写表。
-- ROLLBACK: ALTER TABLE device_info DROP COLUMN IF EXISTS door_state, DROP COLUMN IF EXISTS lock_state, DROP COLUMN IF EXISTS door_reported_at;

ALTER TABLE device_info ADD COLUMN IF NOT EXISTS door_state VARCHAR(8);
ALTER TABLE device_info ADD COLUMN IF NOT EXISTS lock_state VARCHAR(8);
ALTER TABLE device_info ADD COLUMN IF NOT EXISTS door_reported_at TIMESTAMPTZ;

COMMENT ON COLUMN device_info.door_state IS '门磁门状态 open/close（§4.2.16 getDoorInfo 上报，靠 §4.3.2.12 getDoorState 触发）';
COMMENT ON COLUMN device_info.lock_state IS '门磁锁状态 open/close（同上；设备自报口径，不作为结算判据）';
COMMENT ON COLUMN device_info.door_reported_at IS '门磁状态最近上报时间';
