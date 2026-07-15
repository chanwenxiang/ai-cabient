-- 演示视觉映射补全（与 V25 商品目录对齐）

INSERT INTO sku_vision_mapping (class_name, sku_id, min_confidence, mapping_source)
VALUES
    ('can', 'SKU-SODA-001', 0.5, 'YOLO_COCO'),
    ('bowl', 'SKU-NOODLE-001', 0.5, 'YOLO_COCO')
ON CONFLICT (class_name) DO UPDATE SET
    sku_id = EXCLUDED.sku_id,
    min_confidence = EXCLUDED.min_confidence;
