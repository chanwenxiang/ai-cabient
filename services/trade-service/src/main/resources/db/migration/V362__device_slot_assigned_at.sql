-- 图1 对照（叮当小柜，2026-10-15）：货柜商品卡片「上架时间」。
-- device_slot 缺少「SKU 绑定到该货道」的时间戳；新增 assigned_at：
--   写侧：upsert 换绑 SKU 时刷新（DeviceSlotService.applyUpsertFields / doApplyPlanogramTemplate）
--   存量回填：用 updated_at 近似（最早只能追溯到最近一次陈列变更，自己的设计判断，无公开规格）
ALTER TABLE device_slot ADD COLUMN IF NOT EXISTS assigned_at TIMESTAMPTZ;

UPDATE device_slot
SET assigned_at = updated_at
WHERE assigned_sku_id IS NOT NULL
  AND assigned_at IS NULL;
