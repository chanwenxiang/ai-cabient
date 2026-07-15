-- 争议 48h SLA、订单支付渠道、库存扣减、重力柜、免密签约、SKU 扣款置信度

ALTER TABLE dispute_ticket ADD COLUMN IF NOT EXISTS sla_due_at TIMESTAMPTZ;
ALTER TABLE dispute_ticket ADD COLUMN IF NOT EXISTS sla_reminder_at TIMESTAMPTZ;
ALTER TABLE dispute_ticket ADD COLUMN IF NOT EXISTS sla_alerted_at TIMESTAMPTZ;
UPDATE dispute_ticket SET sla_due_at = created_at + INTERVAL '48 hours' WHERE sla_due_at IS NULL;

ALTER TABLE cabinet_order ADD COLUMN IF NOT EXISTS pay_channel VARCHAR(16) NOT NULL DEFAULT 'BALANCE';
ALTER TABLE cabinet_order ADD COLUMN IF NOT EXISTS pay_trade_no VARCHAR(64);
ALTER TABLE cabinet_order ADD COLUMN IF NOT EXISTS inventory_deducted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE cabinet_order ADD COLUMN IF NOT EXISTS refunded_at TIMESTAMPTZ;

ALTER TABLE cabinet_order_line ADD COLUMN IF NOT EXISTS confidence REAL;

ALTER TABLE shopping_session ADD COLUMN IF NOT EXISTS gravity_deltas JSONB;

ALTER TABLE user_info ADD COLUMN IF NOT EXISTS payscore_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE user_info ADD COLUMN IF NOT EXISTS payscore_contract_id VARCHAR(64);
ALTER TABLE user_info ADD COLUMN IF NOT EXISTS alipay_agreement_id VARCHAR(64);
ALTER TABLE user_info ADD COLUMN IF NOT EXISTS pay_preferred_channel VARCHAR(16) NOT NULL DEFAULT 'BALANCE';

ALTER TABLE sku_catalog ADD COLUMN IF NOT EXISTS min_charge_confidence REAL NOT NULL DEFAULT 0.92;

CREATE INDEX IF NOT EXISTS idx_dispute_sla_open ON dispute_ticket (status, sla_due_at) WHERE status = 'OPEN';
