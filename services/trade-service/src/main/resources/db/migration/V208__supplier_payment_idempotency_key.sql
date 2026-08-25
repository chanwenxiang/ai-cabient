-- 供应商付款幂等键：防运营重复提交 / 多节点双付。

ALTER TABLE supplier_payment
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uk_supplier_payment_idempotency_key
    ON supplier_payment (idempotency_key)
    WHERE idempotency_key IS NOT NULL AND idempotency_key <> '';
