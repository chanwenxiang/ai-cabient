-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- TABLES: warehouse_inventory（加1 个索引；不改表结构）
-- LOCK_RISK: low
-- ROLLBACK: 撤销 = DROP INDEX idx_wh_inventory_expiry
-- NOTES: 仓库侧近效期预警（2026-10-07，仓储补货域缺口 #6）。
--
--   背景与「文档说不成立」的原因（2026-10-07 核实）：
--   系统**确实已有** `expiryAlerts` 端点（`OpsReplenishmentController:253`、
--   `MerchantPortalController:272`）与 `SkuCatalog.nearExpiryDays=7` 配置，
--   但它走 `ReplenishmentService.listOpenPullOffTasksPage` ⇒ 数据源是
--   **`PullOffTask`**，而 `PullOffTask` 的字段是 `deviceId` / `lotId`
--   （`PullOffTask.java:18,22`）⇒ **只覆盖设备侧批次，仓库侧完全没有**。
--   ⇒ 「有端点」≠「覆盖仓库」，缺口成立。
--
--   为什么**不加新表**、只加索引：
--   近效期预警是**可从 `warehouse_inventory.expiry_date` 实时算出来的**，
--   不需要物化「预警任务」—— 设备侧需要 `PullOffTask` 是因为它要驱动
--   「下架」这个**动作**；仓库侧只是「提醒哪些批次快到期了」，**没有动作要驱动**。
--   物化成任务表会带来「过期了但任务还开着」这类清理问题。
--
--   索引必要性：预警查询形如
--   `WHERE warehouse_id = ? AND expiry_date <= CURRENT_DATE + N`
--   现有的 `idx_wh_inventory_wh_sku`(warehouse_id, sku_id) 前缀可用，
--   但 expiry_date 无索引 ⇒ 批次量大时会退化成全表扫。

CREATE INDEX IF NOT EXISTS idx_wh_inventory_expiry
    ON warehouse_inventory (expiry_date, warehouse_id);

COMMENT ON INDEX idx_wh_inventory_expiry IS 'V317：仓库侧近效期预警查询用（expiry_date <= 今天 + N）。不物化预警任务，避免「过期任务未关」问题';
