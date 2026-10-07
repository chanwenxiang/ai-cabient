-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- TABLES: inventory_write_off（补4 列：责任归属 + 原因分类）
-- LOCK_RISK: low
-- ROLLBACK: 撤销 = ALTER TABLE inventory_write_off DROP COLUMN reason_category,
--           responsible_party, claim_no, claim_amount_cents
-- NOTES: 货损责任归属（2026-10-07，仓储补货域缺口 #1）。
--
--   背景：三方差距审计列出「货损/盘亏**责任归属**缺失」为 P1。取证确认
--   `InventoryWriteOff` 只有 deviceId/skuId/batchNo/quantity/reason/costCents/
--   operatorId —— **无法回答「这笔损失该找谁赔」**。后果是连锁的：
--     · 无法生成供应商对账单（缺口 #8 依赖此项）
--     · 盘亏不能联动供应商应付（缺口 #4 依赖此项）
--     · 责任方是商户还是供应商/物流，只能靠人回忆
--   ⇒ 这是仓储域的**前置项**，④⑧都压在它上面。
--
--   ⚠️ 本迁移**只加列不加约束**，理由见下：
--   1) 责任归属是**事后认定**的 —— 出库时未必知道这批货最后算谁的。
--      强制非空会让历史流程无法写入 ⇒ 允许 NULL，「未认定」与「无责任方」可区分。
--   2) 老数据（reason 已有 'EXPIRED' 这类值）reason_category 可从 reason 回填，
--      但**不能对老数据臆造责任方** —— 那等于伪造证据。
--
--   `reason_category` 为何存在：`reason` 是自由文本（UI 现在传 'EXPIRED'，
--   见 StockHealthView.vue:437），无法统计「过期/破损/丢失各占多少」。
--   加一列受控枚举才能做**分类统计与责任判定**。
--   🔴 不加 CHECK 约束：生产库尚未归一化 reason 取值，加了会写入失败。
--     待 reason 归一化后（另一次迁移）再补约束 —— 已在代码注释里标注。

ALTER TABLE inventory_write_off
    ADD COLUMN IF NOT EXISTS reason_category      VARCHAR(24),
    ADD COLUMN IF NOT EXISTS responsible_party    VARCHAR(16),
    ADD COLUMN IF NOT EXISTS claim_no             VARCHAR(64),
    ADD COLUMN IF NOT EXISTS claim_amount_cents   BIGINT;

-- 从已有 reason 回填分类：只映射**明确可判定**的值，其余留 NULL。
-- 🔴 刻意只映射 3 个高置信值，不猜其余：
--    'EXPIRED' → EXPIRED 是唯一可从 UI 源码证实的取值（StockHealthView.vue:437）。
--    其余自由文本若强行归类就是**猜测**，而错误分类会污染责任判定。
UPDATE inventory_write_off
SET reason_category = 'EXPIRED'
WHERE reason_category IS NULL
  AND UPPER(TRIM(reason)) IN ('EXPIRED', '过期', '临期');

-- 责任归属维度的查询索引（对账/追责时按责任方筛）
CREATE INDEX IF NOT EXISTS idx_write_off_responsible
    ON inventory_write_off (responsible_party, created_at DESC);

COMMENT ON COLUMN inventory_write_off.reason_category IS '原因分类（受控枚举，未归一化前不加 CHECK）。用于分类统计与责任判定。回填只映射 EXPIRED —— 其余不猜';
COMMENT ON COLUMN inventory_write_off.responsible_party IS '责任方：MERCHANT / SUPPLIER / LOGISTICS / NONE / UNDETERMINED。NULL = 尚未认定（与 NONE=认定无责任方不同）';
COMMENT ON COLUMN inventory_write_off.claim_no IS '理赔单号（对账时与供应商/物流结算单勾稽）';
COMMENT ON COLUMN inventory_write_off.claim_amount_cents IS '索赔金额，单位分。与 cost_cents（损失成本）不同：cost 是账面损失，claim 是向责任方主张的金额';