-- V7 视觉映射数据（依赖 sku_catalog 中 SKU-SODA-001 / SKU-APPLE-001，见 V11）
INSERT INTO sku_catalog (sku_id, sku_name, price_cents)
VALUES ('SKU-SODA-001', '汽水', 350),
       ('SKU-APPLE-001', '苹果', 500)
ON CONFLICT (sku_id) DO NOTHING;

INSERT INTO sku_vision_mapping (class_name, sku_id, min_confidence, mapping_source)
VALUES ('bottle', 'SKU-SODA-001', 0.5, 'YOLO_COCO'),
       ('cup', 'SKU-SODA-001', 0.5, 'YOLO_COCO'),
       ('apple', 'SKU-APPLE-001', 0.5, 'YOLO_COCO')
ON CONFLICT (class_name) DO UPDATE
    SET sku_id = EXCLUDED.sku_id,
        min_confidence = EXCLUDED.min_confidence,
        mapping_source = EXCLUDED.mapping_source;

INSERT INTO aliyun_category_mapping (category_id, category_name, sku_id, min_confidence)
VALUES ('demo-soda', '饮料', 'SKU-SODA-001', 0.5),
       ('demo-apple', '水果', 'SKU-APPLE-001', 0.5)
ON CONFLICT (category_id) DO NOTHING;
