-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- TABLES: warehouse_stocktake_line（补 1 列：差异原因分类）
-- LOCK_RISK: low
-- ROLLBACK: 撤销 = ALTER TABLE warehouse_stocktake_line DROP COLUMN diff_reason
-- NOTES: 盘点差异原因分类（2026-10-07，仓储补货域缺口 #2）。
--
--   背景：三方差距审计列出「仓库侧盘点差异无原因分类、直接改账」。
--   取证确认：`WarehouseService:284` 把 reason **硬编码成字符串 "STOCKTAKE"**
--   写进库存流水 —— 于是所有盘点差异在流水中长得一模一样，
--   **事后无法区分「正常损耗 / 错记 / 偷拿 / 系统bug」**。
--
--   为什么这个分类比想象中重要（不只是统计）：
--   盘亏是**要追责的**。没有分类，就只能看到「这个仓这个月亏了 300 件」，
--   无法回答「是不是有人在偷」。有了分类才能定位异常模式。
--   B方旧系统正是靠人工分类 `stockType/remark` 做到这一点。
--
--   🔴 **刻意只加在「盘点差异」这一层，不改 `InventoryWriteOff`**：
--   盘点是「账实核对」，报损是「主动核销」，两条链路语义不同
--   （盘点差异可能是系统记错，报损是已知损耗）。混用会让统计失真。
--   V311 的 `inventory_write_off.reason_category` 是另一条链路，别混。
--
--   存量数据：老差异 reason 恒为 "STOCKTAKE"，**不臆造分类** ⇒ 只对
--   「已有明显归因」的情况回填，其余留 NULL（NULL = 未分类，是要治理的问题）。

ALTER TABLE warehouse_stocktake_line
    ADD COLUMN IF NOT EXISTS diff_reason VARCHAR(24);

COMMENT ON COLUMN warehouse_stocktake_line.diff_reason IS '盘点差异原因分类（受控枚举，NULL=未分类。与 inventory_write_off.reason_category 是两条链路，勿混用）。用途：定位异常模式（如某仓反复盘亏 ⇒ 疑似管理问题）';