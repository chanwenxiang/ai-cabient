-- MIGRATION_KIND: schema
-- MA-177（R23-A，2026-11-15 拍板执行）：device_sku_lot 补 (device_id, sku_id, batch_no) 唯一键。
--
-- 成因：V24:24 只建普通索引 idx_lot_device_sku(device_id,sku_id)；R18-4 的 upsert 收口只覆盖
-- 聚合表 device_sku_inventory ⇒ 并发建档（两笔补货同批号同时首建）可产生重复批次行 +
-- syncAggregateInventory 首建竞态 DuplicateKeyException。
-- 前置核查（2026-11-15 dev 库实跑）：GROUP BY device_id,sku_id,batch_no HAVING count(*)>1 ⇒ 0 行
-- （全表 13 行），无存量重复，无需清理即可建键。batch_no 允许 NULL 时 PG 唯一索引视 NULL 为互异，
-- 不影响「无批次号」的历史行。
-- LOCK_RISK: low（表仅 13 行）
-- TABLES: device_sku_lot (~13 rows)
-- NOTES: DDL；幂等性由 Flyway 一次性执行保证；不改任何历史迁移

CREATE UNIQUE INDEX IF NOT EXISTS uq_lot_device_sku_batch
    ON device_sku_lot (device_id, sku_id, batch_no);
