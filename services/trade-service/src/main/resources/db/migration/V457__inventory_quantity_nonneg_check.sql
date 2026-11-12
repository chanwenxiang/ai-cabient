-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: high（两张表全表校验 + ACCESS EXCLUSIVE，dev 数据量小秒级；生产须低峰执行，
--             见 NOTES 的存量预检——必须先确认零负库存再上线）
-- TABLES: device_sku_inventory(约千行)、device_sku_lot(约千行，只加约束不改数据)
-- NOTES: 集群就绪 P-1（TRI_CLIENT_BACKLOG §六）第 2 半——DB 层兜底约束。
--        第 1 半是应用层单语句原子扣减（InventoryLotService.deductQuantityAtomic /
--        InventoryService.applyDelta 同批改造：UPDATE … SET qty = qty-? WHERE qty >= ?，
--        影响行数 0 = 库存不足）。此前两表扣减都是「读→算→写」：批次表无 version 无 CAS，
--        汇总表虽有 mapper.save() 的版本 CAS，但都依赖 Redis 设备锁作唯一防线——多副本下
--        锁租约过期/Redis 故障时无第二道闸。CHECK (quantity >= 0) 让任何形态的并发超扣
--        在 DB 层当场失败（宁拒勿负），与 V454 引用 uk_wallet_ledger_ref 的「DB 级兜底」
--        同一设计取向。
--        上线前置预检（本迁移在 dev 库 2026-11-12 实跑通过，双表负库存均为 0）：
--          SELECT COUNT(*) FROM device_sku_inventory WHERE quantity < 0;
--          SELECT COUNT(*) FROM device_sku_lot       WHERE quantity < 0;
--        存在负库存行时本迁移会直接失败——那是有意为之（fail-closed），先查明原因再放行。

ALTER TABLE device_sku_inventory
    ADD CONSTRAINT chk_device_sku_inventory_qty_nonneg CHECK (quantity >= 0);

ALTER TABLE device_sku_lot
    ADD CONSTRAINT chk_device_sku_lot_qty_nonneg CHECK (quantity >= 0);
