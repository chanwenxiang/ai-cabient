-- COMPETITOR_REF: CB-017
-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- V327（E7 缺口 #9）：仓库月结单实体化——两步法（CB-017）：
--   差异先落「待处理」，处置时逐行选（进索赔台账=写 inventory_write_off / 标记正常损耗）。
-- 🔴 行业口径修正（CB-017）：盘亏**不生成应付**——走管理费用/其他应收款；
--    有责任方的差异经 inventory_write_off 进 E4a 索赔台账，追偿实际发生再走支付流程。

-- 月结主单：仓库×月唯一（UNIQUE 钉死一张；重生成=删 DRAFT 重建；APPROVED 锁单不可改删）。
CREATE TABLE warehouse_monthly_close (
    close_id              BIGSERIAL PRIMARY KEY,
    warehouse_id          VARCHAR(32)  NOT NULL,
    year_month            VARCHAR(7)   NOT NULL,
    status                VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    loss_qty              INT          NOT NULL DEFAULT 0,
    surplus_qty           INT          NOT NULL DEFAULT 0,
    loss_amount_cents     BIGINT       NOT NULL DEFAULT 0,
    surplus_amount_cents  BIGINT       NOT NULL DEFAULT 0,
    line_count            INT          NOT NULL DEFAULT 0,
    approved_by           BIGINT,
    approved_by_name      VARCHAR(64),
    approved_at           TIMESTAMPTZ,
    remark                VARCHAR(256),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_monthly_close_wh_month UNIQUE (warehouse_id, year_month),
    CONSTRAINT ck_monthly_close_status CHECK (status IN ('DRAFT', 'APPROVED'))
);
CREATE INDEX idx_monthly_close_status ON warehouse_monthly_close (status, created_at DESC);

-- 月结行：照 WarehouseMonthlyCloseLineDto 12 列 + 金额化 + 差异处置。
CREATE TABLE warehouse_monthly_close_line (
    line_id           BIGSERIAL PRIMARY KEY,
    close_id          BIGINT       NOT NULL,
    sku_id            VARCHAR(64)  NOT NULL,
    sku_name          VARCHAR(128),
    opening_qty       INT          NOT NULL DEFAULT 0,
    purchase_in_qty   INT          NOT NULL DEFAULT 0,
    transfer_in_qty   INT          NOT NULL DEFAULT 0,
    transfer_out_qty  INT          NOT NULL DEFAULT 0,
    restock_qty       INT          NOT NULL DEFAULT 0,
    return_qty        INT          NOT NULL DEFAULT 0,
    loss_qty          INT          NOT NULL DEFAULT 0,
    expected_qty      INT          NOT NULL DEFAULT 0,
    counted_qty       INT,
    gap_qty           INT,
    gap_amount_cents  BIGINT,
    -- PENDING 待认定（盘亏默认）/ CLAIM 进索赔台账 / NORMAL_LOSS 正常损耗 / SURPLUS 盘盈待查
    gap_disposition   VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    claim_write_off_id BIGINT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_monthly_close_line_close FOREIGN KEY (close_id)
        REFERENCES warehouse_monthly_close (close_id) ON DELETE CASCADE,
    CONSTRAINT uk_monthly_close_line UNIQUE (close_id, sku_id),
    CONSTRAINT ck_monthly_close_line_disposition
        CHECK (gap_disposition IN ('PENDING', 'CLAIM', 'NORMAL_LOSS', 'SURPLUS'))
);
CREATE INDEX idx_monthly_close_line_disposition ON warehouse_monthly_close_line (close_id, gap_disposition);
