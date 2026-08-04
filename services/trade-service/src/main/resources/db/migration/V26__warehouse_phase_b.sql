-- Phase B: 简易 WMS — 仓库、批次库存、入库/出库

CREATE TABLE IF NOT EXISTS warehouse (
    warehouse_id    VARCHAR(32)  PRIMARY KEY,
    warehouse_name  VARCHAR(128) NOT NULL,
    address         VARCHAR(256),
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS warehouse_inventory (
    inventory_id    BIGSERIAL    PRIMARY KEY,
    warehouse_id    VARCHAR(32)  NOT NULL REFERENCES warehouse (warehouse_id),
    sku_id          VARCHAR(64)  NOT NULL,
    batch_no        VARCHAR(64)  NOT NULL,
    production_date DATE,
    expiry_date     DATE         NOT NULL,
    quantity        INT          NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (warehouse_id, sku_id, batch_no)
);

CREATE INDEX IF NOT EXISTS idx_wh_inv_expiry ON warehouse_inventory (warehouse_id, expiry_date);

CREATE TABLE IF NOT EXISTS warehouse_inbound (
    inbound_id      BIGSERIAL    PRIMARY KEY,
    warehouse_id    VARCHAR(32)  NOT NULL REFERENCES warehouse (warehouse_id),
    ref_no          VARCHAR(64),
    status          VARCHAR(16)  NOT NULL DEFAULT 'COMPLETED',
    operator_id     BIGINT,
    notes           TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS warehouse_inbound_line (
    line_id         BIGSERIAL    PRIMARY KEY,
    inbound_id      BIGINT       NOT NULL REFERENCES warehouse_inbound (inbound_id),
    sku_id          VARCHAR(64)  NOT NULL,
    batch_no        VARCHAR(64)  NOT NULL,
    production_date DATE,
    expiry_date     DATE         NOT NULL,
    quantity        INT          NOT NULL
);

CREATE TABLE IF NOT EXISTS warehouse_outbound (
    outbound_id     BIGSERIAL    PRIMARY KEY,
    warehouse_id    VARCHAR(32)  NOT NULL REFERENCES warehouse (warehouse_id),
    route_id        BIGINT       REFERENCES replenishment_route (route_id),
    status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    assignee_user_id BIGINT,
    notes           TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    shipped_at      TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_wh_outbound_route ON warehouse_outbound (route_id);

CREATE TABLE IF NOT EXISTS warehouse_outbound_line (
    line_id         BIGSERIAL    PRIMARY KEY,
    outbound_id     BIGINT       NOT NULL REFERENCES warehouse_outbound (outbound_id),
    device_id       VARCHAR(64),
    sku_id          VARCHAR(64)  NOT NULL,
    batch_no        VARCHAR(64)  NOT NULL,
    expiry_date     DATE,
    quantity        INT          NOT NULL,
    picked          BOOLEAN      NOT NULL DEFAULT FALSE
);

ALTER TABLE replenishment_task ADD COLUMN IF NOT EXISTS outbound_id BIGINT;
ALTER TABLE replenishment_task ADD COLUMN IF NOT EXISTS check_in_at TIMESTAMPTZ;

INSERT INTO warehouse (warehouse_id, warehouse_name, address, status)
VALUES ('WH-DEMO-001', '演示中心仓', '上海市浦东新区', 'ACTIVE')
ON CONFLICT (warehouse_id) DO NOTHING;

INSERT INTO warehouse_inventory (warehouse_id, sku_id, batch_no, production_date, expiry_date, quantity)
VALUES
    ('WH-DEMO-001', 'SKU-DEMO-001', 'B-WH-COLA-01', CURRENT_DATE - 10, CURRENT_DATE + 260, 80),
    ('WH-DEMO-001', 'SKU-SODA-001', 'B-WH-SPRITE-01', CURRENT_DATE - 8, CURRENT_DATE + 262, 60),
    ('WH-DEMO-001', 'SKU-WATER-001', 'B-WH-WATER-01', CURRENT_DATE - 5, CURRENT_DATE + 360, 100),
    ('WH-DEMO-001', 'SKU-SNACK-001', 'B-WH-CHIPS-01', CURRENT_DATE - 15, CURRENT_DATE + 165, 40),
    ('WH-DEMO-001', 'SKU-MILK-001', 'B-WH-MILK-01', CURRENT_DATE - 3, CURRENT_DATE + 177, 30),
    ('WH-DEMO-001', 'SKU-NOODLE-001', 'B-WH-NOODLE-01', CURRENT_DATE - 20, CURRENT_DATE + 250, 50)
ON CONFLICT (warehouse_id, sku_id, batch_no) DO UPDATE SET
    quantity = EXCLUDED.quantity,
    expiry_date = EXCLUDED.expiry_date;
