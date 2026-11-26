-- MIGRATION_KIND: schema
-- COMPETITOR_REF: CB-036
-- MIGRATION_REVIEWED: yes（将 V508 的通知失败状态纳入状态枚举门禁的最终定义）
-- LOCK_RISK: low（notification_log 约 15 行；DROP/ADD 只取短暂锁，NOT VALID 不扫存量）
-- TABLES: notification_log (~15 rows)
-- NOTES: V508 已把 FAILED 写入字典与 CHECK；V513 以 NOT VALID 形态重建同一约束，使状态门禁以最后定义为准。部署前探针零脏值后由运维 VALIDATE。

ALTER TABLE notification_log
  DROP CONSTRAINT IF EXISTS chk_notification_log_status;

ALTER TABLE notification_log
  ADD CONSTRAINT chk_notification_log_status
  CHECK (status IN ('SENT', 'FAILED'))
  NOT VALID;
