-- Wave3: warehouse transfer + purchase receive target warehouse
CREATE TABLE IF NOT EXISTS warehouse_transfer_order (
    transfer_id      BIGSERIAL PRIMARY KEY,
    transfer_no      VARCHAR(64)  NOT NULL,
    from_warehouse_id VARCHAR(64) NOT NULL,
    to_warehouse_id   VARCHAR(64) NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    operator_id      BIGINT,
    notes            VARCHAR(512),
    shipped_at       TIMESTAMPTZ,
    received_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_wh_transfer_no UNIQUE (transfer_no),
    CONSTRAINT chk_wh_transfer_status CHECK (status IN ('DRAFT', 'SHIPPED', 'RECEIVED', 'CANCELLED')),
    CONSTRAINT chk_wh_transfer_diff CHECK (from_warehouse_id <> to_warehouse_id)
);
CREATE INDEX IF NOT EXISTS idx_wh_transfer_status ON warehouse_transfer_order (status, created_at DESC);

CREATE TABLE IF NOT EXISTS warehouse_transfer_line (
    line_id     BIGSERIAL PRIMARY KEY,
    transfer_id BIGINT       NOT NULL REFERENCES warehouse_transfer_order (transfer_id) ON DELETE CASCADE,
    sku_id      VARCHAR(64)  NOT NULL,
    batch_no    VARCHAR(64)  NOT NULL DEFAULT '',
    expiry_date DATE,
    quantity    INT          NOT NULL,
    CONSTRAINT chk_wh_transfer_qty CHECK (quantity > 0)
);
CREATE INDEX IF NOT EXISTS idx_wh_transfer_line_tid ON warehouse_transfer_line (transfer_id);
