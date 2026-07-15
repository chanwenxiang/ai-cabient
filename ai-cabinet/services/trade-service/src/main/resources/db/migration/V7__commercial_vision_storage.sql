-- 商业落地：阿里云类目映射 + YOLO 映射扩展（非自研 CV）

ALTER TABLE sku_vision_mapping
    ADD COLUMN IF NOT EXISTS mapping_source VARCHAR(32) NOT NULL DEFAULT 'YOLO_COCO';

INSERT INTO sku_vision_mapping (class_name, sku_id, min_confidence, mapping_source)
VALUES ('bottle', 'SKU-SODA-001', 0.5, 'YOLO_COCO'),
       ('cup', 'SKU-SODA-001', 0.5, 'YOLO_COCO'),
       ('apple', 'SKU-APPLE-001', 0.5, 'YOLO_COCO')
ON CONFLICT (class_name) DO UPDATE
    SET sku_id = EXCLUDED.sku_id,
        min_confidence = EXCLUDED.min_confidence,
        mapping_source = EXCLUDED.mapping_source;

CREATE TABLE IF NOT EXISTS aliyun_category_mapping (
    category_id    VARCHAR(64) PRIMARY KEY,
    category_name  VARCHAR(128),
    sku_id         VARCHAR(64) NOT NULL REFERENCES sku_catalog(sku_id),
    min_confidence REAL NOT NULL DEFAULT 0.5
);

-- 示例：真实类目 ID 需调用阿里云 ClassifyCommodity 后由运营配置
INSERT INTO aliyun_category_mapping (category_id, category_name, sku_id, min_confidence)
VALUES ('demo-soda', '饮料', 'SKU-SODA-001', 0.5),
       ('demo-apple', '水果', 'SKU-APPLE-001', 0.5)
ON CONFLICT (category_id) DO NOTHING;
