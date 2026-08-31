-- V239: core trade display redundancy + UK gaps (Chinese comments)

ALTER TABLE cabinet_order ADD COLUMN IF NOT EXISTS device_name VARCHAR(128);
COMMENT ON COLUMN cabinet_order.device_name IS U&'\8BBE\5907\540D\79F0\5197\4F59\FF08\7ED3\7B97\5199\5165\FF09';

ALTER TABLE cabinet_order ADD COLUMN IF NOT EXISTS merchant_id VARCHAR(64);
COMMENT ON COLUMN cabinet_order.merchant_id IS U&'\5546\6237ID\5197\4F59\FF08\7ED3\7B97\5199\5165\FF09';

ALTER TABLE cabinet_order ADD COLUMN IF NOT EXISTS merchant_name VARCHAR(128);
COMMENT ON COLUMN cabinet_order.merchant_name IS U&'\5546\6237\540D\79F0\5197\4F59\FF08\7ED3\7B97\5199\5165\FF09';

ALTER TABLE shopping_session ADD COLUMN IF NOT EXISTS device_name VARCHAR(128);
COMMENT ON COLUMN shopping_session.device_name IS U&'\8BBE\5907\540D\79F0\5197\4F59\FF08\5F00\95E8\5199\5165\FF09';

CREATE INDEX IF NOT EXISTS idx_cabinet_order_merchant_id ON cabinet_order (merchant_id);

-- backfill from device / merchant
UPDATE cabinet_order o
SET device_name = d.device_name,
    merchant_id = d.merchant_id,
    merchant_name = m.merchant_name,
    updated_at = COALESCE(o.updated_at, o.created_at, now())
FROM device_info d
LEFT JOIN merchant m ON m.merchant_id = d.merchant_id
WHERE o.device_id = d.device_id
  AND (o.device_name IS NULL OR o.merchant_id IS NULL OR o.merchant_name IS NULL);

UPDATE shopping_session s
SET device_name = d.device_name
FROM device_info d
WHERE s.device_id = d.device_id
  AND s.device_name IS NULL;

-- UK gaps (partial unique where nullable)
CREATE UNIQUE INDEX IF NOT EXISTS uk_payment_operation_gateway_trade_no
    ON payment_operation (gateway_trade_no)
    WHERE gateway_trade_no IS NOT NULL AND gateway_trade_no <> '';

CREATE UNIQUE INDEX IF NOT EXISTS uk_cabinet_order_pay_trade_no
    ON cabinet_order (pay_trade_no)
    WHERE pay_trade_no IS NOT NULL AND pay_trade_no <> '';
