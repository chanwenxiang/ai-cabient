CREATE TABLE IF NOT EXISTS supplier (
    supplier_id     VARCHAR(32)  PRIMARY KEY,
    supplier_name   VARCHAR(128) NOT NULL,
    contact_name    VARCHAR(64),
    contact_phone   VARCHAR(32),
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS purchase_order (
    purchase_order_id BIGSERIAL    PRIMARY KEY,
    supplier_id       VARCHAR(32)  NOT NULL REFERENCES supplier (supplier_id),
    warehouse_id      VARCHAR(32)  NOT NULL REFERENCES warehouse (warehouse_id),
    status            VARCHAR(16)  NOT NULL DEFAULT 'CREATED',
    ref_no            VARCHAR(64),
    operator_id       BIGINT,
    notes             TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    received_at       TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_purchase_order_status ON purchase_order (status, created_at DESC);

CREATE TABLE IF NOT EXISTS purchase_order_line (
    line_id           BIGSERIAL    PRIMARY KEY,
    purchase_order_id BIGINT       NOT NULL REFERENCES purchase_order (purchase_order_id),
    sku_id            VARCHAR(64)  NOT NULL,
    batch_no          VARCHAR(64)  NOT NULL,
    production_date   DATE,
    expiry_date       DATE         NOT NULL,
    ordered_qty       INT          NOT NULL,
    received_qty      INT          NOT NULL DEFAULT 0,
    unit_cost_cents   INT          NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_purchase_line_order ON purchase_order_line (purchase_order_id);

CREATE TABLE IF NOT EXISTS warehouse_movement (
    movement_id   BIGSERIAL    PRIMARY KEY,
    warehouse_id  VARCHAR(32)  NOT NULL REFERENCES warehouse (warehouse_id),
    sku_id        VARCHAR(64)  NOT NULL,
    batch_no      VARCHAR(64),
    movement_type VARCHAR(32)  NOT NULL,
    delta_qty     INT          NOT NULL,
    ref_type      VARCHAR(32),
    ref_id        VARCHAR(64),
    operator_id   BIGINT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_wh_movement_ref ON warehouse_movement (ref_type, ref_id);
CREATE INDEX IF NOT EXISTS idx_wh_movement_sku ON warehouse_movement (warehouse_id, sku_id, created_at DESC);

ALTER TABLE warehouse_inbound ADD COLUMN IF NOT EXISTS purchase_order_id BIGINT REFERENCES purchase_order (purchase_order_id);
ALTER TABLE warehouse_inbound_line ADD COLUMN IF NOT EXISTS unit_cost_cents INT NOT NULL DEFAULT 0;

ALTER TABLE purchase_order_line ADD COLUMN IF NOT EXISTS quality_status VARCHAR(16) NOT NULL DEFAULT 'PENDING';
ALTER TABLE purchase_order_line ADD COLUMN IF NOT EXISTS quality_note VARCHAR(256);
ALTER TABLE purchase_order_line ADD COLUMN IF NOT EXISTS rejected_qty INT NOT NULL DEFAULT 0;

ALTER TABLE warehouse_outbound ADD COLUMN IF NOT EXISTS handover_status VARCHAR(16) NOT NULL DEFAULT 'PENDING';
ALTER TABLE warehouse_outbound ADD COLUMN IF NOT EXISTS handover_operator_id BIGINT;
ALTER TABLE warehouse_outbound ADD COLUMN IF NOT EXISTS handed_over_at TIMESTAMPTZ;
ALTER TABLE warehouse_outbound_line ADD COLUMN IF NOT EXISTS handover_status VARCHAR(16) NOT NULL DEFAULT 'PENDING';

ALTER TABLE order_revenue_split ADD COLUMN IF NOT EXISTS settlement_batch_no VARCHAR(64);
ALTER TABLE order_revenue_split ADD COLUMN IF NOT EXISTS settle_after DATE;
ALTER TABLE order_revenue_split ADD COLUMN IF NOT EXISTS settled_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS payment_operation (
    operation_id     VARCHAR(64) PRIMARY KEY,
    order_id         VARCHAR(32) NOT NULL,
    operation_type   VARCHAR(24) NOT NULL,
    amount_cents     INT NOT NULL,
    channel          VARCHAR(16) NOT NULL,
    status           VARCHAR(16) NOT NULL,
    idempotency_key  VARCHAR(128) NOT NULL UNIQUE,
    gateway_trade_no VARCHAR(64),
    reason           VARCHAR(128),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_payment_operation_order ON payment_operation (order_id, created_at DESC);

INSERT INTO supplier (supplier_id, supplier_name, contact_name, contact_phone, status)
VALUES ('SUP-DEMO-001', 'Demo Beverage Supplier', 'Demo Buyer', '13800138001', 'ACTIVE')
ON CONFLICT (supplier_id) DO NOTHING;
