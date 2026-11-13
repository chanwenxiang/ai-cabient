-- MIGRATION_IMMUTABLE_EXEMPT: S14 修复批——merchant_replenishment_request（V346 DROP）的 CHECK 约束为死语句，移除；
--   其余 7 表约束保留。测试库重建消化 checksum。
-- MIGRATION_KIND: schema
-- COMPETITOR_REF: CB-036
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: medium（8 条 ADD CONSTRAINT ... CHECK ... NOT VALID：只取短暂 ACCESS EXCLUSIVE 且**不扫存量行**；
--            不带 NOT VALID 会全表扫并持锁。VALIDATE CONSTRAINT 解耦为部署前脏值探针后的运维动作）
-- TABLES: purchase_order / merchant_replenishment_request / replenishment_task / replenishment_route / warehouse_in_transit / warehouse_stocktake / warehouse_stocktake_line / warehouse_outbound（订单链第 3 批，本批 8 列）
-- NOTES: R20 P3-1 第 3 批（docs/AUDIT_FOUR_END_R20_2026-11-12.md §二十三 蓝图 / §二十七 回执）。
--        值域由 check-status-enum-parity.mjs 自动对齐 SysDictBootstrap 字典族 ↔ shared-dict ↔ CHECK 字面量，
--        三方双向相等、无人工枚举白名单；新增状态未同步约束 ⇒ CI 红。
--        🔴 禁止用 dev 稀疏值域推枚举（dev 100 个状态列仅 13 个有 CHECK、distinct 仅 113 行）。
--        两条新增自动判据：① 目标列 DDL DEFAULT 必须 ∈ CHECK 值域（否则默认插入即被拒）；
--        ② 带实体类名锚点的源码写入值 set(Cls::getStatus/X, ...) 必须 ⊆ CHECK 值域。
--        CB-036 边界：可扩展经营归属字段仍走字典零迁移，本批只覆盖封闭状态机列（有意不同）。
--        同行公开资料只披露状态枚举、未披露 DB 约束形态 ⇒ 无公开规格，属自己的设计判断（铁律 47）。
--        CHECK (col IN (...)) 对 NULL 天然放行（SQL 三值逻辑，CHECK 只在 FALSE 时拒绝）。
--        部署前脏值探针（逐列，NULL 显式放行）：
--          SELECT DISTINCT <col> FROM <table> WHERE <col> IS NOT NULL
--            AND UPPER(TRIM(<col>)) NOT IN (<下方同一字面量集合>);
--        零脏值后再按表执行 ALTER TABLE <table> VALIDATE CONSTRAINT <constraint>;
--        ⚠️ VALIDATE 故意不写进迁移：大表全扫 + 存量脏值会让 Flyway 失败＝部署失败。

ALTER TABLE purchase_order
  ADD CONSTRAINT chk_purchase_order_status
  CHECK (status IN ('CANCELLED','CREATED','PARTIAL_RECEIVED','PENDING_APPROVAL','RECEIVED','REJECTED'))
  NOT VALID;

-- merchant_replenishment_request 已于 V346 DROP：原 CHECK 约束为死语句，已移除（S14 表名声明集修复）。

ALTER TABLE replenishment_task
  ADD CONSTRAINT chk_replenishment_task_status
  CHECK (status IN ('CANCELLED','COMPLETED','IN_PROGRESS','PENDING'))
  NOT VALID;

ALTER TABLE replenishment_route
  ADD CONSTRAINT chk_replenishment_route_status
  CHECK (status IN ('CANCELLED','COMPLETED','IN_PROGRESS','PLANNED'))
  NOT VALID;

ALTER TABLE warehouse_in_transit
  ADD CONSTRAINT chk_warehouse_in_transit_status
  CHECK (status IN ('CANCELLED','DAMAGED','IN_TRANSIT','LOST','RECEIVED'))
  NOT VALID;

ALTER TABLE warehouse_stocktake
  ADD CONSTRAINT chk_warehouse_stocktake_status
  CHECK (status IN ('ADJUSTED','CANCELLED','COMPLETED','DRAFT','IN_PROGRESS'))
  NOT VALID;

ALTER TABLE warehouse_stocktake_line
  ADD CONSTRAINT chk_warehouse_stocktake_line_status
  CHECK (status IN ('ADJUSTED','DIFF','MATCHED','PENDING'))
  NOT VALID;

ALTER TABLE warehouse_outbound
  ADD CONSTRAINT chk_warehouse_outbound_status
  CHECK (status IN ('CANCELLED','DRAFT','PICKED','SHIPPED'))
  NOT VALID;
