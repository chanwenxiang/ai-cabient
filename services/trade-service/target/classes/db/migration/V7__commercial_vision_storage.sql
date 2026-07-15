-- 商业落地：阿里云类目映射 + YOLO 映射扩展（非自研 CV）
-- 数据种子见 V56（需在 sku_catalog 种子 SKU 存在后插入）

ALTER TABLE sku_vision_mapping
    ADD COLUMN IF NOT EXISTS mapping_source VARCHAR(32) NOT NULL DEFAULT 'YOLO_COCO';

CREATE TABLE IF NOT EXISTS aliyun_category_mapping (
    category_id    VARCHAR(64) PRIMARY KEY,
    category_name  VARCHAR(128),
    sku_id         VARCHAR(64) NOT NULL REFERENCES sku_catalog(sku_id),
    min_confidence REAL NOT NULL DEFAULT 0.5
);
