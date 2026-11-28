-- MIGRATION_KIND: schema
-- COMPETITOR_REF: CB-088
-- MIGRATION_REVIEWED: yes（站内信语义幂等；仅约束新增 inapp: 前缀，历史 NULL 不受影响）
-- LOCK_RISK: medium（notification_log 为大表；部分索引仅覆盖新增前缀，仍须低峰执行）
-- TABLES: notification_log
-- NOTES: 同一业务事件重放只保留一条站内信；外部渠道继续由 outbox 稳定键与行锁去重。

CREATE UNIQUE INDEX IF NOT EXISTS uk_notification_log_inapp_semantic
  ON notification_log (client_msg_id)
  WHERE channel = 'IN_APP'
    AND status = 'SENT'
    AND client_msg_id LIKE 'inapp:%';
