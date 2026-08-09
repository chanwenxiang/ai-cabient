-- 仓库货位档案
CREATE TABLE IF NOT EXISTS warehouse_bin (
    bin_id       BIGSERIAL   PRIMARY KEY,
    warehouse_id VARCHAR(32) NOT NULL REFERENCES warehouse (warehouse_id),
    bin_code     VARCHAR(32) NOT NULL,
    bin_name     VARCHAR(64),
    status       VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (warehouse_id, bin_code)
);
CREATE INDEX IF NOT EXISTS idx_warehouse_bin_wh ON warehouse_bin (warehouse_id, status);

-- 货位库存：按 货位+商品+批次 记录
CREATE TABLE IF NOT EXISTS warehouse_bin_stock (
    id             BIGSERIAL   PRIMARY KEY,
    bin_id         BIGINT      NOT NULL REFERENCES warehouse_bin (bin_id) ON DELETE CASCADE,
    sku_id         VARCHAR(64) NOT NULL,
    batch_no       VARCHAR(64) NOT NULL,
    production_date DATE,
    expiry_date    DATE,
    quantity       INT         NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (bin_id, sku_id, batch_no)
);
CREATE INDEX IF NOT EXISTS idx_warehouse_bin_stock_sku
    ON warehouse_bin_stock (sku_id, batch_no, expiry_date);
