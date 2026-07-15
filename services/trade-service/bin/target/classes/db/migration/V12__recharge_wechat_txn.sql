-- 充值订单扩展：微信交易号、退款时间

ALTER TABLE recharge_order ADD COLUMN IF NOT EXISTS wx_transaction_id VARCHAR(64);
ALTER TABLE recharge_order ADD COLUMN IF NOT EXISTS refunded_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_recharge_status ON recharge_order (status);
