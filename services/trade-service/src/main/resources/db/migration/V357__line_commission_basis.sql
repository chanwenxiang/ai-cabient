-- MIGRATION_KIND: backfill
-- 线长提成双基数（对齐老系统 MachineCommissionLink.type：0=按销售额 / 1=按销量即补货数量；
-- 2026-10-14 拍板对齐老系统）。粒度：按线长（老系统按人×机器，新系统线长比例本就人粒度，
-- 人×机器差异化待补货单数据完善后扩展——设计判断，无公开规格）。
ALTER TABLE line_manager
    ADD COLUMN commission_basis VARCHAR(16) NOT NULL DEFAULT 'GMV'
        COMMENT '提成基数：GMV=销售额(默认) / RESTOCK_QTY=补货数量',
    ADD COLUMN commission_per_unit_cents BIGINT NOT NULL DEFAULT 0
        COMMENT 'RESTOCK_QTY 模式：每件补货佣金（分/件）；GMV 模式忽略';
