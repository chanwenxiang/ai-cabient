-- COMPETITOR_REF: CB-033
-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- TABLES: merchant_withdraw_request
-- LOCK_RISK: low
-- ROLLBACK: 可回滚新增列与索引；已产生的重试状态随列一并丢弃
-- NOTES: 备付恢复重试状态。渠道结果作为权威，本地资金池不再拦截自动重试。

ALTER TABLE merchant_withdraw_request
    ADD COLUMN IF NOT EXISTS next_payout_retry_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS payout_retry_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_payout_block_reason VARCHAR(512);

CREATE INDEX IF NOT EXISTS idx_merchant_withdraw_funding_retry
    ON merchant_withdraw_request (status, next_payout_retry_at)
    WHERE next_payout_retry_at IS NOT NULL
      AND status IN ('APPROVED', 'FAILED');
