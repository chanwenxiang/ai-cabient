-- MIGRATION_KIND: schema
-- COMPETITOR_REF: CB-020
-- 商户费用台账（2026-10-14 拍板）：日结批生成费用归属日记录（台账视角），钱包余额真扣
-- （资金视角，钱包流水为准）——两处双记录；线下收款后运营标记 SETTLED。
-- 老系统（ego-automat）点位费用「只登记不扣款」，本表补齐结清状态闭环。
-- PostgreSQL 方言（2026-10-14 修正：初版误写 MySQL 语法，未在任何库应用过，原位改写）。
CREATE TABLE merchant_fee_ledger (
    ledger_id    BIGSERIAL PRIMARY KEY,
    merchant_id  VARCHAR(32)  NOT NULL,
    fee_date     DATE         NOT NULL,
    fee_type     VARCHAR(16)  NOT NULL,  -- REN=租金 / DATA=流量费（费用项可扩展）
    amount_cents BIGINT       NOT NULL,  -- 当日应缴费用（分）
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING', -- PENDING=待结清 / SETTLED=线下已结清
    settled_at   TIMESTAMPTZ  NULL,
    remark       VARCHAR(200) NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_merchant_fee_date_type
    ON merchant_fee_ledger (merchant_id, fee_date, fee_type);
CREATE INDEX IF NOT EXISTS idx_merchant_fee_status
    ON merchant_fee_ledger (merchant_id, status);
CREATE INDEX IF NOT EXISTS idx_merchant_fee_status_date
    ON merchant_fee_ledger (status, fee_date);
