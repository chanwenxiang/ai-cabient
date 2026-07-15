-- 商品目录扩展字段（图片、分类、条码等）

ALTER TABLE sku_catalog
    ADD COLUMN IF NOT EXISTS image_url VARCHAR(512),
    ADD COLUMN IF NOT EXISTS description TEXT,
    ADD COLUMN IF NOT EXISTS category VARCHAR(64),
    ADD COLUMN IF NOT EXISTS barcode VARCHAR(64),
    ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_sku_catalog_category ON sku_catalog (category);
CREATE INDEX IF NOT EXISTS idx_sku_catalog_barcode ON sku_catalog (barcode);
