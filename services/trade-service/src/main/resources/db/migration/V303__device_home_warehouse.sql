-- MIGRATION_KIND: schema
-- 分仓：柜机归线（所属仓库）。勿占 V301/V302。
-- 不在 device_info 上建非 CONCURRENTLY 索引（热表门禁）。

ALTER TABLE device_info
    ADD COLUMN IF NOT EXISTS home_warehouse_id VARCHAR(32);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'device_info_home_warehouse_id_fkey'
    ) THEN
        ALTER TABLE device_info
            ADD CONSTRAINT device_info_home_warehouse_id_fkey
            FOREIGN KEY (home_warehouse_id) REFERENCES warehouse (warehouse_id)
            ON DELETE SET NULL;
    END IF;
END $$;

COMMENT ON COLUMN device_info.home_warehouse_id IS '所属分仓（补货归线）';
