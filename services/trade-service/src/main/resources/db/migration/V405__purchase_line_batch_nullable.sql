-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- TABLES: purchase_order_line (~0 行生产数据，UAT 测试库 30 行内)
-- NOTES: DROP NOT NULL 仅改 pg_attribute 元数据，不重写表、不回填历史行；需 ACCESS EXCLUSIVE 但瞬时完成。
--        无索引/CHECK 依赖这两列（V34 只建了 idx_purchase_line_order(purchase_order_id)，
--        V218 只加 sku_id 外键），故不必重建任何对象。
-- 采购单「下单时批次/效期常常还不知道」：V34 建表把 batch_no / expiry_date 定成 NOT NULL，
-- 而服务层早已按「下单可空 + 收货必填」实现（ProcurementService.validatePurchaseLine 的
-- receiving 分支、matchLine 的 null 批次兜底、ProcurementServiceTest:147 断言下单落 null），
-- 两边口径不一致 ⇒ 下单不填批次直接 500（UAT §7.20 ⓱19 活栈实测）。本迁移把列改可空，
-- 让服务层既有的「收货必填」校验成为唯一闸门；FEFO 入库始终取收货时回写的值（processReceiveLine:491）。
ALTER TABLE purchase_order_line
    ALTER COLUMN batch_no DROP NOT NULL;
ALTER TABLE purchase_order_line
    ALTER COLUMN expiry_date DROP NOT NULL;

COMMENT ON COLUMN purchase_order_line.batch_no IS
    '批次号：下单可空（V405），收货时必填并回写订单行；未填收货即 400（ProcurementService.validatePurchaseLine）';
COMMENT ON COLUMN purchase_order_line.expiry_date IS
    '到期日期：下单可空（V405），收货时必填并回写；仓内 FEFO 批次以收货回写值为准';
