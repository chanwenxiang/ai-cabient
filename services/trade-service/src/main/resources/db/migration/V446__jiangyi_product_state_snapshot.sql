-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- NOTES: 给 sku_jiangyi_link 增第七章采集状态快照列（全部可空、无回填，重放安全）。
--        状态来自 §7.2 七态机（NEW/READY/NEED_PHONE/REJECTED/REVIEWING/CABINET_PENDING/CABINET_REVIEWING）。
--        不新增表：状态属于「商户商品库里的条码」，而每个在用的条码都已有一条挂接行。
-- TABLES: sku_jiangyi_link (ALTER ADD COLUMN ×4)

ALTER TABLE sku_jiangyi_link
    ADD COLUMN IF NOT EXISTS jiangyi_state    VARCHAR(32),
    ADD COLUMN IF NOT EXISTS next_action      VARCHAR(48),
    ADD COLUMN IF NOT EXISTS reject_cause     TEXT,
    ADD COLUMN IF NOT EXISTS state_checked_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_sku_jiangyi_link_state
    ON sku_jiangyi_link (jiangyi_state, state_checked_at);

COMMENT ON COLUMN sku_jiangyi_link.jiangyi_state IS
    '将邑第七章 §7.2 商品状态快照（V16 2026-10-24）；NULL=尚未查询/旧数据';
COMMENT ON COLUMN sku_jiangyi_link.next_action IS
    '§7.5/§7.6 返回的 nextAction（CREATE_PRODUCT/RESTOCK/PHONE_CAPTURE/…）';
COMMENT ON COLUMN sku_jiangyi_link.reject_cause IS
    '§7.2 state=REJECTED 时的驳回原因原文';
COMMENT ON COLUMN sku_jiangyi_link.state_checked_at IS
    '最近一次查询商品状态的时间（§7.5/§7.10）';
