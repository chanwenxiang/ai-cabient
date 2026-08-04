-- Phase C: 采购成本、报损、订单 COGS、补货 GPS 签到

ALTER TABLE sku_catalog ADD COLUMN IF NOT EXISTS purchase_cost_cents INT;
ALTER TABLE sku_catalog ADD COLUMN IF NOT EXISTS near_expiry_price_cents INT;

ALTER TABLE cabinet_order_line ADD COLUMN IF NOT EXISTS unit_cost_cents INT;

ALTER TABLE replenishment_task ADD COLUMN IF NOT EXISTS check_in_lat DOUBLE PRECISION;
ALTER TABLE replenishment_task ADD COLUMN IF NOT EXISTS check_in_lng DOUBLE PRECISION;

CREATE TABLE IF NOT EXISTS inventory_write_off (
    write_off_id    BIGSERIAL    PRIMARY KEY,
    device_id       VARCHAR(64)  NOT NULL,
    sku_id          VARCHAR(64)  NOT NULL,
    batch_no        VARCHAR(64),
    quantity        INT          NOT NULL,
    reason          VARCHAR(32)  NOT NULL,
    cost_cents      INT,
    operator_id     BIGINT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_write_off_device ON inventory_write_off (device_id, created_at DESC);

-- 演示 SKU 采购成本（约为售价 55%）
UPDATE sku_catalog SET purchase_cost_cents = 190 WHERE sku_id = 'SKU-DEMO-001' AND purchase_cost_cents IS NULL;
UPDATE sku_catalog SET purchase_cost_cents = 220 WHERE sku_id = 'SKU-SODA-001' AND purchase_cost_cents IS NULL;
UPDATE sku_catalog SET purchase_cost_cents = 110 WHERE sku_id = 'SKU-WATER-001' AND purchase_cost_cents IS NULL;
UPDATE sku_catalog SET purchase_cost_cents = 360 WHERE sku_id = 'SKU-SNACK-001' AND purchase_cost_cents IS NULL;
UPDATE sku_catalog SET purchase_cost_cents = 250 WHERE sku_id = 'SKU-MILK-001' AND purchase_cost_cents IS NULL;
UPDATE sku_catalog SET purchase_cost_cents = 290 WHERE sku_id = 'SKU-NOODLE-001' AND purchase_cost_cents IS NULL;
