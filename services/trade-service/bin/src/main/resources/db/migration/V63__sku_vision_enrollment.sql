-- 商品 ↔ 视觉识别一体化：SKU 主数据扩展 + 存量映射回填

ALTER TABLE sku_catalog
    ADD COLUMN IF NOT EXISTS yolo_class_name VARCHAR(128),
    ADD COLUMN IF NOT EXISTS vision_enrollment_status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    ADD COLUMN IF NOT EXISTS detection_min_confidence REAL NOT NULL DEFAULT 0.5,
    ADD COLUMN IF NOT EXISTS reference_image_urls TEXT;

UPDATE sku_catalog sc
SET yolo_class_name = svm.class_name,
    detection_min_confidence = svm.min_confidence,
    vision_enrollment_status = 'PRODUCTION'
FROM sku_vision_mapping svm
WHERE sc.sku_id = svm.sku_id
  AND svm.mapping_source = 'YOLO_SKU'
  AND (sc.yolo_class_name IS NULL OR sc.yolo_class_name = '');

UPDATE sku_catalog
SET vision_enrollment_status = 'PRODUCTION'
WHERE sku_id IN ('SKU-DEMO-001', 'SKU-SODA-001', 'SKU-WATER-001', 'SKU-SNACK-001', 'SKU-MILK-001', 'SKU-NOODLE-001')
  AND vision_enrollment_status = 'DRAFT';
