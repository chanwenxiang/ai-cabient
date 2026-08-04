-- 采购退货：关联已收货采购单，扣减仓库库存并记流水

ALTER TABLE purchase_order_line
    ADD COLUMN IF NOT EXISTS returned_qty INT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS purchase_return (
    return_id          BIGSERIAL PRIMARY KEY,
    purchase_order_id  BIGINT       NOT NULL REFERENCES purchase_order (purchase_order_id),
    warehouse_id       VARCHAR(32)  NOT NULL,
    supplier_id        VARCHAR(64)  NOT NULL,
    status             VARCHAR(16)  NOT NULL DEFAULT 'COMPLETED',
    notes              TEXT,
    operator_id        BIGINT,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_purchase_return_po ON purchase_return (purchase_order_id);

CREATE TABLE IF NOT EXISTS purchase_return_line (
    line_id            BIGSERIAL PRIMARY KEY,
    return_id          BIGINT       NOT NULL REFERENCES purchase_return (return_id),
    purchase_line_id   BIGINT       NOT NULL,
    sku_id             VARCHAR(64)  NOT NULL,
    batch_no           VARCHAR(64)  NOT NULL,
    quantity           INT          NOT NULL CHECK (quantity > 0)
);

CREATE INDEX IF NOT EXISTS idx_purchase_return_line_return ON purchase_return_line (return_id);
