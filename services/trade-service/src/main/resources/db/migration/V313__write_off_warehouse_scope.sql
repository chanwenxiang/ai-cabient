-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- TABLES: inventory_write_off（补 warehouse_id + 放宽 device_id）
-- LOCK_RISK: low
-- ROLLBACK: 撤销 = ALTER TABLE inventory_write_off DROP COLUMN warehouse_id,
--           ALTER TABLE inventory_write_off ALTER COLUMN device_id SET NOT NULL
-- NOTES: 仓库侧核销/报废（2026-10-07，仓储补货域缺口 #5）。
--
--   背景：`InventoryOpsService.writeOff` 实现完整（`InventoryWriteOffMapper` 都在），
--   但入口 `deviceValidationService.requireDevice(request.deviceId())` ⇒ **只支持设备侧**。
--   仓库里的货（破损/过期/丢失）**没有核销入口** ——
--   仓库侧盘点差异只能走 `adjustStocktake` 改账，无法记录「为什么损」。
--
--   为什么复用同一张表而不是新建 `warehouse_write_off`：
--   报损是**同一件事**（把损失记录下来并归因），只是发生位置不同。
--   分表会让「全平台损耗分析」变成 union 两张结构相似的表，
--   而 V311 的 `reason_category` / `responsible_party` / `claim_amount_cents`
--   也就无法跨位置汇总。
--
--   🔴 `device_id` 必须放宽为 NULL：
--   原列是 `NOT NULL` + FK 到 `device_info`。仓库侧报损**没有 device_id**，
--   若沿用 NOT NULL 则仓库侧根本写不进去。
--   ⇒ 改为可空，并加 CHECK「device_id 与 warehouse_id **恰好有一个非空**」——
--   这样既允许仓库侧写入，又**不允许两边都空**（那等于不知道报损发生在哪，
--   是数据质量问题），也不允许两边都有（同一笔损耗不该同时挂设备与仓库）。
--
--   ⚠️ 不动 `device_id` 的 FK：保留它，设备侧报损仍能校验设备存在性。

ALTER TABLE inventory_write_off
    ADD COLUMN IF NOT EXISTS warehouse_id VARCHAR(64);

-- device_id 放宽为可空（原本 NOT NULL）
ALTER TABLE inventory_write_off
    ALTER COLUMN device_id DROP NOT NULL;

-- 恰好一边非空
ALTER TABLE inventory_write_off
    ADD CONSTRAINT ck_write_off_location CHECK (
        (device_id IS NOT NULL AND warehouse_id IS NULL)
            OR (device_id IS NULL AND warehouse_id IS NOT NULL)
    );

-- 仓库侧报损查询（按仓库 + 时间倒序）
CREATE INDEX IF NOT EXISTS idx_write_off_warehouse
    ON inventory_write_off (warehouse_id, created_at DESC);

-- 位置归属的查询索引（跨位置汇总用）
CREATE INDEX IF NOT EXISTS idx_write_off_device
    ON inventory_write_off (device_id, created_at DESC);

COMMENT ON COLUMN inventory_write_off.warehouse_id IS 'V313：仓库侧报损时填；与 device_id 恰好一边非空（ck_write_off_location 保证）';
COMMENT ON COLUMN inventory_write_off.device_id IS 'V313 起可空（仓库侧报损无设备）。FK 仍保留，设备侧写入仍校验设备存在性';