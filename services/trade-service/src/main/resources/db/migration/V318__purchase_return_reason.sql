-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- TABLES: purchase_return（补 3 列：退货原因分类 / 责任方 / 是否残次品）
-- LOCK_RISK: low
-- ROLLBACK: 撤销 = ALTER TABLE purchase_return
--           DROP COLUMN reason_category, responsible_party, defective_flag
-- NOTES: 采购退货原因分类 / 残次品处置（2026-10-07，仓储补货域缺口 #6）。
--
--   背景与取证：核实 `CreatePurchaseReturnRequest`（只有 notes + lines）
--   与 `PurchaseReturn`（status/notes/…）⇒ **完全没有原因分类**，全仓也搜不到
--   「残次/defective/scrap」任何概念。⇒ 缺口成立。
--
--   为什么这个分类比「看起来只是个字段」重要：
--   采购退货是**唯一能把货退回供应商的动作** ⇒ 它的原因直接决定
--   ① 能不能索赔/ ② 责任在谁（供方发错 vs 我方发错 vs 物流损坏）
--   ③ 下次还该不该跟这家供应商合作。
--   没有分类，所有退货在报表里长得一样 ⇒ **「退得最多的是谁」这个问题无法回答**。
--
--   设计取舍（与 V311 `inventory_write_off` 分工，不要混）：
--   - `purchase_return`：货**离开仓库退给供应商**（货权要转移、要冲减应付）
--   - `inventory_write_off`：货**还在/还在仓内**，记损耗与归因（不转移货权）
--   ⇒ 同一批货可能先报损（仓内）再退货（出仓），两段链路都要留痕。
--
--   枚举语义与 `WriteOffReasonCategory`（V311）**刻意对齐**，
--   这样「报废原因」的统计口径在两条链路上可合并；
--   但 `returnReasonCategory` 只允许退货场景适用的值（见应用层校验），DB 不加 CHECK。

ALTER TABLE purchase_return
    ADD COLUMN IF NOT EXISTS reason_category  VARCHAR(24),
    ADD COLUMN IF NOT EXISTS responsible_party VARCHAR(16),
    ADD COLUMN IF NOT EXISTS defective_flag   BOOLEAN;

-- 🔴 存量数据**不臆造分类**：老单据没填就是没填
--   （留NULL =「未分类」是要治理的问题，而不是随便猜一个）。
COMMENT ON COLUMN purchase_return.reason_category IS 'V318：退货原因分类（受控枚举，语义与 WriteOffReasonCategory 对齐；NULL=未分类）';
COMMENT ON COLUMN purchase_return.responsible_party IS 'V318：责任方 SUPPLIER/LOGISTICS/MERCHANT/NONE/UNDETERMINED。NULL=尚未认定（与 NONE 语义不同）';
COMMENT ON COLUMN purchase_return.defective_flag IS 'V318：是否残次品（true=商品本身有问题：破损/变质/规格不符，需供应商理赔或索赔）。null=未标记';
