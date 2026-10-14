-- MIGRATION_KIND: backfill
-- 线长提成双基数（对齐老系统 MachineCommissionLink.type：0=按销售额 / 1=按销量即补货数量）。
-- 2026-10-14 二次修正：双基数最终落在 line_route_member（V358，补货员侧）+ line_manager
-- 保留 GMV 比例（线长只有销售提成，对齐老系统）；本迁移保留兼容列（已随 0e6f5808 提交，
-- 未在任何库应用过，语法原位改写为 PostgreSQL）。
ALTER TABLE line_manager
    ADD COLUMN IF NOT EXISTS commission_basis VARCHAR(16) NOT NULL DEFAULT 'GMV',
    ADD COLUMN IF NOT EXISTS commission_per_unit_cents BIGINT NOT NULL DEFAULT 0;
