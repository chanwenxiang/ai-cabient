-- 供应商账期与信用额度
ALTER TABLE supplier ADD COLUMN IF NOT EXISTS payment_terms_days INT NOT NULL DEFAULT 30;
ALTER TABLE supplier ADD COLUMN IF NOT EXISTS credit_limit_cents BIGINT;

-- 应付账款：一张采购单对应一条应付记录，收货累加、退货冲减、付款核销
CREATE TABLE IF NOT EXISTS supplier_payable (
    payable_id        BIGSERIAL   PRIMARY KEY,
    supplier_id       VARCHAR(32) NOT NULL REFERENCES supplier (supplier_id),
    purchase_order_id BIGINT      NOT NULL REFERENCES purchase_order (purchase_order_id),
    warehouse_id      VARCHAR(32) REFERENCES warehouse (warehouse_id),
    amount_cents      BIGINT      NOT NULL DEFAULT 0,
    paid_amount_cents BIGINT      NOT NULL DEFAULT 0,
    status            VARCHAR(16) NOT NULL DEFAULT 'UNPAID',
    due_date          DATE        NOT NULL,
    notes             VARCHAR(256),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    paid_at           TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_supplier_payable_po ON supplier_payable (purchase_order_id);
CREATE INDEX IF NOT EXISTS idx_supplier_payable_supplier ON supplier_payable (supplier_id, status, due_date);

-- 付款记录
CREATE TABLE IF NOT EXISTS supplier_payment (
    payment_id   BIGSERIAL   PRIMARY KEY,
    supplier_id  VARCHAR(32) NOT NULL REFERENCES supplier (supplier_id),
    payable_id   BIGINT      NOT NULL REFERENCES supplier_payable (payable_id),
    amount_cents BIGINT      NOT NULL,
    operator_id  BIGINT,
    notes        VARCHAR(256),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_supplier_payment_payable ON supplier_payment (payable_id);
