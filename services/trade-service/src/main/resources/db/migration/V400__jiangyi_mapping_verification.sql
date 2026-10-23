-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- TABLES: jiangyi_device (~十行)
-- COMPETITOR_REF: CB-065
-- 重训后映射复核闸口：将邑 classId = classes.txt 按 textName 排序后的行下标（台账 H-27，
-- 65→69 类同批商品整体平移：雪碧 25→29），厂商每次重训都会让「编号」指到别的商品。
-- 因此映射行必须绑定到「人工审阅过的 classes 版本」才允许结算：classes_version = 柜机当前
-- 模型（下发回执回填），mapping_verified_version = 最近一次人工复核通过时审阅的版本，
-- 两者相等才算已复核；未复核 → 识别整单 fail-closed 转 DISPUTED、在售面下发空表。
-- NOTES: 两列可空、无默认值，ADD COLUMN 走 metadata-only，不重写表。
-- ROLLBACK: ALTER TABLE jiangyi_device DROP COLUMN IF EXISTS mapping_verified_version, DROP COLUMN IF EXISTS mapping_verified_at;

ALTER TABLE jiangyi_device ADD COLUMN IF NOT EXISTS mapping_verified_version VARCHAR(64);
ALTER TABLE jiangyi_device ADD COLUMN IF NOT EXISTS mapping_verified_at TIMESTAMPTZ;
COMMENT ON COLUMN jiangyi_device.mapping_verified_version IS
    '最近一次人工复核通过时审阅的 classes 版本（classes.txt 内容 sha256 前 12 位）；与 classes_version 相等才允许识别结算';
COMMENT ON COLUMN jiangyi_device.mapping_verified_at IS
    '映射复核通过时间；模型下发确认/未匹配模型回执会撤销该戳（复核失效，需重新重算并确认）';
