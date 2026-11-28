-- MIGRATION_KIND: backfill（新表 + scheduled_task 种子行）
-- COMPETITOR_REF: CB-088
-- MIGRATION_REVIEWED: yes（通知外发持久化重试队列；业务事务内入队，提交后投递）
-- LOCK_RISK: low（全新表 + scheduled_task 单行 INSERT）
-- TABLES: notification_dispatch_outbox (new), scheduled_task (+1)
-- NOTES: 解决同步渠道失败无重试、退款通知未覆盖余额/mock/部分退款、afterCommit 失败静默丢失；幂等键按 audience+user+merchant+template+biz+内容派生。

CREATE TABLE IF NOT EXISTS notification_dispatch_outbox (
    id BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(64) NOT NULL UNIQUE,
    payload_json TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sent_at TIMESTAMPTZ,
    CONSTRAINT chk_notification_dispatch_outbox_status
        CHECK (status IN ('PENDING', 'FAILED', 'SENT'))
);

CREATE INDEX IF NOT EXISTS idx_notification_outbox_due
    ON notification_dispatch_outbox (status, next_retry_at, id);

COMMENT ON TABLE notification_dispatch_outbox IS '通知外发持久化重试队列；业务事务内入队，提交后投递';
COMMENT ON COLUMN notification_dispatch_outbox.idempotency_key IS '按受众、用户、商户、模板、业务单与内容派生的稳定幂等键';
COMMENT ON COLUMN notification_dispatch_outbox.payload_json IS 'NotificationDispatchMessage JSON，重试时原样还原';
COMMENT ON COLUMN notification_dispatch_outbox.status IS 'PENDING 待投递 / FAILED 失败退避 / SENT 已投递';
COMMENT ON COLUMN notification_dispatch_outbox.next_retry_at IS '下次允许投递时间';

INSERT INTO scheduled_task (task_key, task_name, task_group, schedule_desc, remark)
VALUES ('notification-outbox-retry', '通知外发重试队列', 'SYSTEM', '每 60 秒',
        '提交后投递失败的通知按退避策略重试；与业务事务解耦')
ON CONFLICT (task_key) DO NOTHING;
