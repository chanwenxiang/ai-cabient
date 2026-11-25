-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes（REFUND_PROCESSING 为 17 字符，原 VARCHAR(16) 会拒绝真实退款处理中状态）
-- COMPETITOR_REF: CB-061（退款逐笔台账与渠道幂等；异步退款完成后补跑本地下游）
-- TABLES: payment_operation
-- LOCK_RISK: low（VARCHAR 16→32 只改元数据，不重写表；索引重建锁定支付流水表）
-- ROLLBACK: 仅在确认不存在 REFUND_PROCESSING/REFUND_FAILED 后执行 ALTER TABLE payment_operation ALTER COLUMN status TYPE VARCHAR(16)
-- NOTES: 同时把 REFUND_FAILED+PENDING/CHANNEL_FAILED 的充值退款纳入续跑索引，覆盖补偿前崩溃窗口。

ALTER TABLE payment_operation
    ALTER COLUMN status TYPE VARCHAR(32);

DROP INDEX IF EXISTS idx_payment_operation_refund_resume;

CREATE INDEX idx_payment_operation_refund_resume
    ON payment_operation (created_at)
    WHERE channel = 'WECHAT'
      AND status IN ('REFUND_PROCESSING', 'REFUND_FAILED', 'COMPLETED')
      AND refund_context_json IS NOT NULL;
