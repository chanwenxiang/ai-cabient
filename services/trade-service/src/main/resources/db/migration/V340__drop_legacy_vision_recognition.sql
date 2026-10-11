-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low（sku_catalog 行数小，DROP COLUMN 取短 ACCESS EXCLUSIVE；sku_vision_mapping 为旧链小表）
-- TABLES: sku_vision_mapping (~10) | sku_catalog (~50)
-- NOTES: 旧云识别链退役（识别只认将邑端侧）。删前备份：sku_vision_mapping_bak_20261011 / sku_catalog_vision_cols_bak_20261011。三列均为旧 YOLO 入驻专属，将邑链不读写。
-- 旧云识别链退役（2026-10-11，docs/JIANGYI_ONLY_FINAL_VERDICT_2026-10-11.md 批5）：
-- 识别已只认将邑端侧（classId 映射 + sku_jiangyi_link），旧 YOLO 映射表与
-- sku_catalog 三个旧识别字段一并下线。删前备份同库留档。
-- COMPETITOR_REF: CB-022 / CB-023

CREATE TABLE sku_vision_mapping_bak_20261011 AS
SELECT * FROM sku_vision_mapping;

DROP TABLE sku_vision_mapping;

CREATE TABLE sku_catalog_vision_cols_bak_20261011 AS
SELECT sku_id, yolo_class_name, vision_enrollment_status, reference_image_urls
FROM sku_catalog;

ALTER TABLE sku_catalog DROP COLUMN IF EXISTS yolo_class_name;
ALTER TABLE sku_catalog DROP COLUMN IF EXISTS vision_enrollment_status;
ALTER TABLE sku_catalog DROP COLUMN IF EXISTS reference_image_urls;
