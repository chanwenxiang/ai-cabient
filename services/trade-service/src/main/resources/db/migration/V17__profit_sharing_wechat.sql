-- 微信分账 API 扩展字段（账本 + 可选微信提交）

ALTER TABLE order_revenue_split
    ADD COLUMN IF NOT EXISTS wechat_out_order_no VARCHAR(64),
    ADD COLUMN IF NOT EXISTS wechat_transaction_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS failure_reason VARCHAR(512);

CREATE INDEX IF NOT EXISTS idx_order_revenue_split_status ON order_revenue_split (status);
