-- SKU 专用 YOLO 类名映射（mapping_source=YOLO_SKU）
-- 与 vision-service/training/data.yaml 及 classes.json 对齐
-- 生产启用 cabinet-skus-v*.pt 后，在运营后台停用 YOLO_COCO 映射

INSERT INTO sku_vision_mapping (class_name, sku_id, min_confidence, mapping_source)
VALUES
    ('cola_330ml', 'SKU-DEMO-001', 0.55, 'YOLO_SKU'),
    ('sprite_500ml', 'SKU-SODA-001', 0.55, 'YOLO_SKU'),
    ('water_550ml', 'SKU-WATER-001', 0.50, 'YOLO_SKU'),
    ('chips_70g', 'SKU-SNACK-001', 0.55, 'YOLO_SKU'),
    ('milk_250ml', 'SKU-MILK-001', 0.60, 'YOLO_SKU'),
    ('noodle_bowl', 'SKU-NOODLE-001', 0.55, 'YOLO_SKU')
ON CONFLICT (class_name) DO UPDATE SET
    sku_id = EXCLUDED.sku_id,
    min_confidence = EXCLUDED.min_confidence,
    mapping_source = EXCLUDED.mapping_source;
