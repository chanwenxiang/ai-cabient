-- Phase 4: 争议工单扩展、SKU 视觉映射

ALTER TABLE dispute_ticket ADD COLUMN IF NOT EXISTS items JSONB;
ALTER TABLE dispute_ticket ADD COLUMN IF NOT EXISTS resolution_items JSONB;

CREATE TABLE IF NOT EXISTS sku_vision_mapping (
    class_name   VARCHAR(64) PRIMARY KEY,
    sku_id       VARCHAR(64) NOT NULL REFERENCES sku_catalog(sku_id),
    min_confidence REAL NOT NULL DEFAULT 0.5
);

INSERT INTO sku_vision_mapping (class_name, sku_id, min_confidence)
VALUES ('bottle', 'SKU-DEMO-001', 0.5),
       ('cup', 'SKU-DEMO-001', 0.5)
ON CONFLICT (class_name) DO NOTHING;
