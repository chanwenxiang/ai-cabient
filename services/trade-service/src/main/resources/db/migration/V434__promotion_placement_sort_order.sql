-- MIGRATION_KIND: schema
-- 活动展示位排序（2026-11-02 用户要求：轮播顺序由运营定义「谁前谁后」）。
-- 默认 0：存量数据保持原有 activity_id/placement_id 顺序不变。
ALTER TABLE promotion_placement
    ADD COLUMN IF NOT EXISTS sort_order integer NOT NULL DEFAULT 0;

COMMENT ON COLUMN promotion_placement.sort_order IS '轮播顺序，数值小在前；相同值按 activity_id、placement_id';
