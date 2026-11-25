-- MA-451：采购收货按仓/批分配，退货按原收货分配扣减。
-- COMPETITOR_REF: CB-009
-- MIGRATION_KIND: schema
-- TABLES: purchase_receive_allocation
-- NOTES: 修复多次部分收货覆盖订单仓/批导致退货扣错仓批；新表只记录既有收货事实，不改变采购业务规则。
CREATE TABLE IF NOT EXISTS purchase_receive_allocation (
    allocation_id BIGSERIAL PRIMARY KEY,
    purchase_order_id BIGINT NOT NULL,
    purchase_line_id BIGINT NOT NULL,
    sku_id VARCHAR(64) NOT NULL,
    warehouse_id VARCHAR(64) NOT NULL,
    batch_no VARCHAR(64) NOT NULL,
    received_qty INT NOT NULL CHECK (received_qty > 0),
    returned_qty INT NOT NULL DEFAULT 0 CHECK (returned_qty >= 0 AND returned_qty <= received_qty),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_purchase_receive_allocation_order
        FOREIGN KEY (purchase_order_id) REFERENCES purchase_order (purchase_order_id),
    CONSTRAINT fk_purchase_receive_allocation_line
        FOREIGN KEY (purchase_line_id) REFERENCES purchase_order_line (line_id)
);

CREATE INDEX IF NOT EXISTS idx_purchase_receive_allocation_line
    ON purchase_receive_allocation (purchase_line_id, allocation_id);
