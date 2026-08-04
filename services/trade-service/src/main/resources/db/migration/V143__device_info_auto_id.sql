-- 设备表增加自增数字 ID（业务主键仍为 device_id；列表「ID」列展示本字段）
ALTER TABLE device_info
    ADD COLUMN IF NOT EXISTS id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE c.relkind = 'S' AND c.relname = 'device_info_id_seq' AND n.nspname = 'public'
    ) THEN
        CREATE SEQUENCE device_info_id_seq;
    END IF;
END $$;

ALTER TABLE device_info
    ALTER COLUMN id SET DEFAULT nextval('device_info_id_seq');

UPDATE device_info SET id = nextval('device_info_id_seq') WHERE id IS NULL;

ALTER TABLE device_info
    ALTER COLUMN id SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uk_device_info_id'
    ) THEN
        ALTER TABLE device_info ADD CONSTRAINT uk_device_info_id UNIQUE (id);
    END IF;
END $$;

SELECT setval(
    'device_info_id_seq',
    GREATEST((SELECT COALESCE(MAX(id), 1) FROM device_info), 1)
);
