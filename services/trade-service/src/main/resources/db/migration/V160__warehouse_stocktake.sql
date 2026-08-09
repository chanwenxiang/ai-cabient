-- 整仓盘点单
CREATE TABLE IF NOT EXISTS warehouse_stocktake (
    stocktake_id   BIGSERIAL   PRIMARY KEY,
    stocktake_no   VARCHAR(32) NOT NULL UNIQUE,
    warehouse_id   VARCHAR(32) NOT NULL REFERENCES warehouse (warehouse_id),
    mode           VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    status         VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    book_qty       INT         NOT NULL DEFAULT 0,
    counted_qty    INT         NOT NULL DEFAULT 0,
    diff_qty       INT         NOT NULL DEFAULT 0,
    diff_line_count INT        NOT NULL DEFAULT 0,
    operator_id    BIGINT,
    notes          VARCHAR(256),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at     TIMESTAMPTZ,
    completed_at   TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_stocktake_wh_status
    ON warehouse_stocktake (warehouse_id, status, created_at DESC);

-- 盘点明细：按 商品+批次 一行
CREATE TABLE IF NOT EXISTS warehouse_stocktake_line (
    line_id        BIGSERIAL   PRIMARY KEY,
    stocktake_id   BIGINT      NOT NULL REFERENCES warehouse_stocktake (stocktake_id) ON DELETE CASCADE,
    sku_id         VARCHAR(64) NOT NULL,
    batch_no       VARCHAR(64) NOT NULL,
    production_date DATE,
    expiry_date    DATE,
    book_qty       INT         NOT NULL DEFAULT 0,
    counted_qty    INT,
    diff_qty       INT         NOT NULL DEFAULT 0,
    status         VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    notes          VARCHAR(256),
    adjusted_at    TIMESTAMPTZ,
    UNIQUE (stocktake_id, sku_id, batch_no)
);
CREATE INDEX IF NOT EXISTS idx_stocktake_line_stocktake
    ON warehouse_stocktake_line (stocktake_id);
