-- MIGRATION_KIND: backfill
-- 分仓第一步：仓库负责人=补货员（对照 easygo 人绑仓）
-- 禁止占用 V301：已用于 user_info.wx_open_id 去重唯一索引

ALTER TABLE warehouse
    ADD COLUMN IF NOT EXISTS manager_user_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'warehouse_manager_user_id_fkey'
    ) THEN
        ALTER TABLE warehouse
            ADD CONSTRAINT warehouse_manager_user_id_fkey
            FOREIGN KEY (manager_user_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;
END $$;

COMMENT ON COLUMN warehouse.manager_user_id IS '分仓负责人（补货员）user_info.user_id';

-- 演示仓尽量绑演示补货员；找不到人则保持空
UPDATE warehouse w
SET manager_user_id = (
    SELECT u.user_id
    FROM user_info u
    WHERE u.phone_number IN ('13800138009', '13800138001')
      AND COALESCE(u.is_deleted, FALSE) = FALSE
    ORDER BY CASE u.phone_number WHEN '13800138009' THEN 0 ELSE 1 END
    LIMIT 1
)
WHERE w.warehouse_id = 'WH-DEMO-001'
  AND w.manager_user_id IS NULL;
