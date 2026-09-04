-- 设备 SKU 库存乐观锁：与分布式锁并存，防脏写
ALTER TABLE device_sku_inventory
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN device_sku_inventory.version IS '乐观锁版本号，每次更新 +1';
