-- Retail-OS / ShelfVision 76 类 → 演示 SKU（YOLO_RETAIL，供 delta 映射）
-- 真实生产需在运营后台按商户商品维护；此处仅映射高频类 + 货架单类兜底

INSERT INTO sku_vision_mapping (class_name, sku_id, min_confidence, mapping_source)
VALUES
    ('q280', 'SKU-SODA-001', 0.45, 'YOLO_RETAIL'),
    ('q13', 'SKU-SODA-001', 0.45, 'YOLO_RETAIL'),
    ('q145', 'SKU-SODA-001', 0.45, 'YOLO_RETAIL'),
    ('q91', 'SKU-SNACK-001', 0.45, 'YOLO_RETAIL'),
    ('q64', 'SKU-SNACK-001', 0.45, 'YOLO_RETAIL'),
    ('q262', 'SKU-MILK-001', 0.45, 'YOLO_RETAIL'),
    ('q289', 'SKU-WATER-001', 0.45, 'YOLO_RETAIL'),
    ('q40', 'SKU-WATER-001', 0.45, 'YOLO_RETAIL'),
    ('q52', 'SKU-NOODLE-001', 0.45, 'YOLO_RETAIL'),
    ('q211', 'SKU-NOODLE-001', 0.45, 'YOLO_RETAIL'),
    -- HF 单类货架模型兜底（download-retail-os-model.ps1 -UseHfShelfFallback）
    ('object', 'SKU-DEMO-001', 0.40, 'YOLO_RETAIL'),
    ('product', 'SKU-DEMO-001', 0.40, 'YOLO_RETAIL'),
    ('Product', 'SKU-DEMO-001', 0.40, 'YOLO_RETAIL')
ON CONFLICT (class_name) DO UPDATE
    SET sku_id = EXCLUDED.sku_id,
        min_confidence = EXCLUDED.min_confidence,
        mapping_source = EXCLUDED.mapping_source;
