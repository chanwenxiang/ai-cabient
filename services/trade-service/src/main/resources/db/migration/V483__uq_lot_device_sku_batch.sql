-- MIGRATION_KIND: schema
-- MIGRATION_IMMUTABLE_EXEMPT: 已应用迁移补存量重复预检（2026-11-16 R23 收口；仅新增 DO 块，索引定义未变；to_regclass('public.uq_lot_device_sku_batch') = uq_lot_device_sku_batch 实测在位；dev 已 repair checksum）
-- MA-177（R23-A，2026-11-15 拍板执行）：device_sku_lot 补 (device_id, sku_id, batch_no) 唯一键。
--
-- 成因：V24:24 只建普通索引 idx_lot_device_sku(device_id,sku_id)；R18-4 的 upsert 收口只覆盖
-- 聚合表 device_sku_inventory ⇒ 并发建档（两笔补货同批号同时首建）可产生重复批次行 +
-- syncAggregateInventory 首建竞态 DuplicateKeyException。
-- 前置核查（2026-11-15 dev 库实跑）：GROUP BY device_id,sku_id,batch_no HAVING count(*)>1 ⇒ 0 行
-- （全表 13 行），无存量重复，无需清理即可建键。batch_no 允许 NULL 时 PG 唯一索引视 NULL 为互异，
-- 不影响「无批次号」的历史行。
-- 🔴 2026-11-16 补：CREATE UNIQUE INDEX 在带重复行的库上会硬失败（Flyway 启动中止）。
-- 本段对非 NULL 批次做显式预检并给出人工合并指引（fail-closed，不自动合并库存真值）。
-- LOCK_RISK: low（表仅 13 行）
-- TABLES: device_sku_lot (~13 rows)
-- NOTES: DDL；幂等性由 Flyway 一次性执行保证；不改任何历史迁移

DO $$
DECLARE
    dupes text;
BEGIN
    SELECT string_agg(device_id || '|' || sku_id || '|' || batch_no, ', ' ORDER BY device_id)
      INTO dupes
      FROM (
        SELECT device_id, sku_id, batch_no
          FROM device_sku_lot
         WHERE batch_no IS NOT NULL
         GROUP BY device_id, sku_id, batch_no
        HAVING count(*) > 1
      ) d;
    IF dupes IS NOT NULL THEN
        RAISE EXCEPTION 'device_sku_lot 存在重复批次行，禁止盲建唯一索引：%', dupes
            USING HINT = '批次账是库存真值：先逐行核对并人工合并重复行（禁止自动合并），再重跑本迁移';
    END IF;
END
$$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_lot_device_sku_batch
    ON device_sku_lot (device_id, sku_id, batch_no);
