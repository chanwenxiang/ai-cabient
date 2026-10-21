-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- TABLES: sku_catalog (~百行), purchase_order_line (~千行)
-- COMPETITOR_REF: CB-061
-- 零售 ERP/WMS 行业通用「多单位换算」范式：采购按件（箱）、库存/销售按支，成本按支折。
-- 件装数（pack_qty）为运营方内部主数据，不在供应商 API 暴露范围内，须手工录入。
-- NOTES: 两张表均为小表；ADD COLUMN 带 DEFAULT 1 不需回填全表（Postgres 11+ 常量默认值走
--        metadata-only），锁风险低。默认 1 = 无箱规，等价于改造前「1 件 = 1 支」的行为。
-- ROLLBACK: ALTER TABLE sku_catalog DROP COLUMN pack_qty; ALTER TABLE purchase_order_line DROP COLUMN pack_qty;

-- SKU 主数据：新增件装数字段（默认 1 表示无箱规，即 1 件 = 1 支）
ALTER TABLE sku_catalog ADD COLUMN IF NOT EXISTS pack_qty INT NOT NULL DEFAULT 1;
COMMENT ON COLUMN sku_catalog.pack_qty IS
    '件装数（每件的支数）：采购按件、库存/货道/销售按支，成本自动按此折算每支；默认 1=无箱规';

-- 采购订单行：快照件装数（防止后续修改 SKU 主数据导致历史订单换算错乱）
ALTER TABLE purchase_order_line ADD COLUMN IF NOT EXISTS pack_qty INT NOT NULL DEFAULT 1;
COMMENT ON COLUMN purchase_order_line.pack_qty IS
    '件装数快照（下单时的每件支数），用于收货时件→支换算与成本折算';
