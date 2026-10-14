-- MIGRATION_KIND: schema
-- COMPETITOR_REF: CB-040
-- 佣金随退款冲正（2026-10-14 用户拍板 CB-040：跨行业官方规则佐证「退款必冲 + 余额不足
-- 记负数 + 90 天追溯窗」；同行无公开规格，机制为我们的设计判断）。冲回不改动原日佣行
-- （line/staff_commission_daily 唯一键 (owner,date,device) 不允许同日负行，且保留原始
-- 留痕符合「数据流转必须有记录」），累计冲回额落本表（唯一键幂等，每日批重算差额递增），
-- 资金视角同步在 line/staff_wallet_ledger 记负向流水（余额不足挂负）。
CREATE TABLE IF NOT EXISTS commission_clawback (
    id              BIGSERIAL PRIMARY KEY,
    owner_type      VARCHAR(8)  NOT NULL, -- MANAGER=线长 / STAFF=补货员(GMV 模式)
    owner_id        BIGINT      NOT NULL, -- manager_id 或 staff user_id
    device_id       VARCHAR(64) NOT NULL,
    biz_date        DATE        NOT NULL, -- 原佣金归属日
    refund_gmv_cents BIGINT     NOT NULL DEFAULT 0, -- 累计冲回对应的退款 GMV（记录留痕）
    order_count     INT         NOT NULL DEFAULT 0, -- 累计冲回单数（线长侧）
    clawback_cents  BIGINT      NOT NULL DEFAULT 0, -- 累计已冲回金额（正数）
    status          VARCHAR(16) NOT NULL DEFAULT 'POSTED',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_commission_clawback
    ON commission_clawback (owner_type, owner_id, device_id, biz_date);
CREATE INDEX IF NOT EXISTS idx_commission_clawback_owner
    ON commission_clawback (owner_type, owner_id);

-- staff_commission_daily 补记发佣时使用的模式与 GMV 基数：GMV 模式冲回按
-- commission×(gmvOld-gmvNow)/gmvOld 比例（不依赖当前费率，费率变更不产生假冲回）；
-- RESTOCK_QTY 模式与销售无关、不冲回（注释口径见 CommissionClawbackService）。
ALTER TABLE staff_commission_daily ADD COLUMN IF NOT EXISTS gmv_cents BIGINT NOT NULL DEFAULT 0;
ALTER TABLE staff_commission_daily ADD COLUMN IF NOT EXISTS commission_basis VARCHAR(16) NOT NULL DEFAULT 'GMV';
