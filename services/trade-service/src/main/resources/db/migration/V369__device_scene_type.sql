-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: high
-- TABLES: device_info（dev 实测个位数行；scene_type 索引走非并发建索引，理由见 NOTES）
-- COMPETITOR_REF: CB-049, CB-018
-- NOTES: 投放场景分类（CB-049，2026-10-16 用户需求「场景类型可改」）：device_info 新增
--       scene_type，把柜机按投放场景打标（办公/校园/工厂/医院/交通枢纽/商场/社区/酒店/健身房）。
--       同行把场景当运营第一分类维度（选品/补货/评估都按场景走，CB-018/049）；
--       一期只做打标，不做场景驱动的选品/补货建议。空=未分类（存量不强制回填）。
-- TABLE: device_info.scene_type VARCHAR(32) NULL（无外键，取值由 shared-dict device_scene_type 约定）
ALTER TABLE device_info ADD COLUMN IF NOT EXISTS scene_type VARCHAR(32);
CREATE INDEX IF NOT EXISTS idx_device_info_scene_type ON device_info (scene_type);
COMMENT ON COLUMN device_info.scene_type IS '投放场景：OFFICE/CAMPUS/FACTORY/HOSPITAL/TRANSIT/MALL/RESIDENCE/HOTEL/GYM；空=未分类（CB-049）';
-- LOCK_RISK 豁免说明（评审记录 2026-10-16）：本脚本含热表 device_info 上的非 CONCURRENTLY 建索引，
-- 按门禁归类 high；实际 device_info 当前规模极小（个位数行），非并发建索引锁窗口毫秒级，
-- 且 CREATE INDEX CONCURRENTLY 无法在 Flyway 事务内执行（OSS 无 per-migration 非事务配置），
-- 评审结论：维持非并发建索引，待生产上线前如 device_info 行数 >10w 再拆独立 CONCURRENTLY 迁移。
