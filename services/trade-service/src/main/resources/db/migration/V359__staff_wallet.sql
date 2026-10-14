-- MIGRATION_KIND: schema
-- COMPETITOR_REF: CB-020
-- 补货员钱包（2026-10-14 用户拍板）：对齐老系统补货员独立分成账户（CommissionAccount，
-- 可提现、支付宝代发线下）。佣金出账时入钱包（资金视角），staff_commission_daily 仍为
-- 台账视角（双记录，口径同商户费用台账+钱包流水）。提现=线下打款后运营标记出账
-- （对外资金线下结算总口径）。
CREATE TABLE IF NOT EXISTS staff_wallet_account (
    staff_user_id  BIGINT PRIMARY KEY,
    balance_cents  BIGINT NOT NULL DEFAULT 0,
    frozen_cents   BIGINT NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS staff_wallet_ledger (
    ledger_id       BIGSERIAL PRIMARY KEY,
    staff_user_id   BIGINT       NOT NULL,
    entry_type      VARCHAR(32)  NOT NULL,
    amount_cents    BIGINT       NOT NULL,
    balance_after   BIGINT       NOT NULL,
    frozen_after    BIGINT       NOT NULL DEFAULT 0,
    ref_type        VARCHAR(32),
    ref_id          VARCHAR(64),
    remark          VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_staff_wallet_ledger_staff
    ON staff_wallet_ledger (staff_user_id, created_at DESC);
