ALTER TABLE recharge_order ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);
ALTER TABLE recharge_order ADD COLUMN IF NOT EXISTS payment_operation_id VARCHAR(64);

UPDATE recharge_order
SET idempotency_key = 'legacy-recharge:' || order_id
WHERE idempotency_key IS NULL;

ALTER TABLE recharge_order ALTER COLUMN idempotency_key SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_recharge_order_idempotency
    ON recharge_order (idempotency_key);
CREATE INDEX IF NOT EXISTS idx_recharge_order_payment_operation
    ON recharge_order (payment_operation_id);
