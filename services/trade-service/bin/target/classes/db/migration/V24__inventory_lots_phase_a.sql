-- Phase A: 批次效期、库存流水、补货行项目、下架任务

ALTER TABLE sku_catalog ADD COLUMN IF NOT EXISTS shelf_life_days INT;
ALTER TABLE sku_catalog ADD COLUMN IF NOT EXISTS near_expiry_days INT NOT NULL DEFAULT 7;
ALTER TABLE sku_catalog ADD COLUMN IF NOT EXISTS block_sale_days_before_expiry INT NOT NULL DEFAULT 0;
ALTER TABLE sku_catalog ADD COLUMN IF NOT EXISTS storage_type VARCHAR(16) NOT NULL DEFAULT 'AMBIENT';

ALTER TABLE cabinet_order_line ADD COLUMN IF NOT EXISTS batch_no VARCHAR(64);

CREATE TABLE IF NOT EXISTS device_sku_lot (
    lot_id          VARCHAR(32)  PRIMARY KEY,
    device_id       VARCHAR(64)  NOT NULL,
    sku_id          VARCHAR(64)  NOT NULL,
    batch_no        VARCHAR(64)  NOT NULL,
    production_date DATE,
    expiry_date     DATE         NOT NULL,
    quantity        INT          NOT NULL DEFAULT 0,
    slot_id         VARCHAR(32),
    status          VARCHAR(16)  NOT NULL DEFAULT 'ON_SALE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_lot_device_sku ON device_sku_lot (device_id, sku_id);
CREATE INDEX IF NOT EXISTS idx_lot_expiry_status ON device_sku_lot (expiry_date, status);

CREATE TABLE IF NOT EXISTS inventory_movement (
    movement_id     BIGSERIAL    PRIMARY KEY,
    device_id       VARCHAR(64)  NOT NULL,
    sku_id          VARCHAR(64)  NOT NULL,
    batch_no        VARCHAR(64),
    movement_type   VARCHAR(32)  NOT NULL,
    delta_qty       INT          NOT NULL,
    ref_type        VARCHAR(32),
    ref_id          VARCHAR(64),
    operator_id     BIGINT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_movement_device ON inventory_movement (device_id, created_at DESC);

CREATE TABLE IF NOT EXISTS replenishment_task_line (
    line_id         BIGSERIAL    PRIMARY KEY,
    task_id         BIGINT       NOT NULL REFERENCES replenishment_task (task_id),
    line_type       VARCHAR(16)  NOT NULL,
    sku_id          VARCHAR(64)  NOT NULL,
    batch_no        VARCHAR(64),
    production_date DATE,
    expiry_date     DATE,
    quantity        INT          NOT NULL,
    slot_id         VARCHAR(32),
    applied         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_replenish_line_task ON replenishment_task_line (task_id);

CREATE TABLE IF NOT EXISTS pull_off_task (
    task_id         BIGSERIAL    PRIMARY KEY,
    device_id       VARCHAR(64)  NOT NULL,
    sku_id          VARCHAR(64)  NOT NULL,
    lot_id          VARCHAR(32),
    batch_no        VARCHAR(64),
    quantity        INT          NOT NULL DEFAULT 1,
    reason          VARCHAR(32)  NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    resolved_at     TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_pull_off_open ON pull_off_task (status, device_id);
