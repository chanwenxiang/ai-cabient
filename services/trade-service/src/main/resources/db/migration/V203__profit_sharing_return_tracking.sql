-- 微信分账回退异步跟踪（查询/重试）

ALTER TABLE order_revenue_split
    ADD COLUMN IF NOT EXISTS wechat_pending_return_no VARCHAR(64),
    ADD COLUMN IF NOT EXISTS wechat_pending_return_cents BIGINT;

CREATE INDEX IF NOT EXISTS idx_order_revenue_split_pending_return
    ON order_revenue_split (wechat_pending_return_no)
    WHERE wechat_pending_return_no IS NOT NULL;
