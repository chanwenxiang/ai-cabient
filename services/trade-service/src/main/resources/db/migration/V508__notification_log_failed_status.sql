-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low（notification_log 小表；DROP/ADD 后立即 VALIDATE）
-- TABLES: notification_log.status
-- NOTES: MA-422：外部通知失败留痕写 FAILED，V477 的单值 SENT 约束会让失败日志 INSERT 自身失败。
ALTER TABLE notification_log
  DROP CONSTRAINT IF EXISTS chk_notification_log_status;
ALTER TABLE notification_log
  ADD CONSTRAINT chk_notification_log_status
  CHECK (status IN ('SENT', 'FAILED'));
ALTER TABLE notification_log
  VALIDATE CONSTRAINT chk_notification_log_status;
