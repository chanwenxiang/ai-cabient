-- 商品主数据：最后修改人（运营账号）
ALTER TABLE sku_catalog
    ADD COLUMN IF NOT EXISTS updated_by_user_id BIGINT,
    ADD COLUMN IF NOT EXISTS updated_by_name VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_sku_catalog_updated_by ON sku_catalog (updated_by_user_id);
