-- 设备 SKU 改价乐观锁：防 admin/merchant 并发覆盖
ALTER TABLE device_sku_price
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN device_sku_price.version IS '乐观锁版本号，每次更新 +1';
