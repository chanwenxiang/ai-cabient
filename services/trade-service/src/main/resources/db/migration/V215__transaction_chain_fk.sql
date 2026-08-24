-- Transaction / audit chain: tie sessions, orders, movements to device_info + user_info.
-- Device links RESTRICT (keep financial/ops history); nullable refs SET NULL on parent delete.

UPDATE ops_exception e
SET device_id = NULL
WHERE e.device_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM device_info d WHERE d.device_id = e.device_id);

UPDATE ops_exception e
SET session_id = NULL
WHERE e.session_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM shopping_session s WHERE s.session_id = e.session_id);

UPDATE ops_exception e
SET order_id = NULL
WHERE e.order_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM cabinet_order o WHERE o.order_id = e.order_id);

UPDATE user_feedback f
SET device_id = NULL
WHERE f.device_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM device_info d WHERE d.device_id = f.device_id);

UPDATE user_feedback f
SET session_id = NULL
WHERE f.session_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM shopping_session s WHERE s.session_id = f.session_id);

DELETE FROM inventory_movement m
WHERE NOT EXISTS (SELECT 1 FROM device_info d WHERE d.device_id = m.device_id)
   OR NOT EXISTS (SELECT 1 FROM sku_catalog s WHERE s.sku_id = m.sku_id);

DELETE FROM order_revenue_split s
WHERE NOT EXISTS (SELECT 1 FROM device_info d WHERE d.device_id = s.device_id);

DELETE FROM cabinet_order o
WHERE NOT EXISTS (SELECT 1 FROM device_info d WHERE d.device_id = o.device_id)
   OR NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = o.user_id)
   OR NOT EXISTS (SELECT 1 FROM shopping_session s WHERE s.session_id = o.session_id);

DELETE FROM shopping_session s
WHERE NOT EXISTS (SELECT 1 FROM device_info d WHERE d.device_id = s.device_id)
   OR NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = s.user_id);

DELETE FROM replenishment_task t
WHERE NOT EXISTS (SELECT 1 FROM device_info d WHERE d.device_id = t.device_id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'shopping_session_device_id_fkey'
          AND conrelid = 'shopping_session'::regclass
    ) THEN
        ALTER TABLE shopping_session
            ADD CONSTRAINT shopping_session_device_id_fkey
            FOREIGN KEY (device_id) REFERENCES device_info (device_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'cabinet_order_device_id_fkey'
          AND conrelid = 'cabinet_order'::regclass
    ) THEN
        ALTER TABLE cabinet_order
            ADD CONSTRAINT cabinet_order_device_id_fkey
            FOREIGN KEY (device_id) REFERENCES device_info (device_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'cabinet_order_user_id_fkey'
          AND conrelid = 'cabinet_order'::regclass
    ) THEN
        ALTER TABLE cabinet_order
            ADD CONSTRAINT cabinet_order_user_id_fkey
            FOREIGN KEY (user_id) REFERENCES user_info (user_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'inventory_movement_device_id_fkey'
          AND conrelid = 'inventory_movement'::regclass
    ) THEN
        ALTER TABLE inventory_movement
            ADD CONSTRAINT inventory_movement_device_id_fkey
            FOREIGN KEY (device_id) REFERENCES device_info (device_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'inventory_movement_sku_id_fkey'
          AND conrelid = 'inventory_movement'::regclass
    ) THEN
        ALTER TABLE inventory_movement
            ADD CONSTRAINT inventory_movement_sku_id_fkey
            FOREIGN KEY (sku_id) REFERENCES sku_catalog (sku_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'order_revenue_split_device_id_fkey'
          AND conrelid = 'order_revenue_split'::regclass
    ) THEN
        ALTER TABLE order_revenue_split
            ADD CONSTRAINT order_revenue_split_device_id_fkey
            FOREIGN KEY (device_id) REFERENCES device_info (device_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'replenishment_task_device_id_fkey'
          AND conrelid = 'replenishment_task'::regclass
    ) THEN
        ALTER TABLE replenishment_task
            ADD CONSTRAINT replenishment_task_device_id_fkey
            FOREIGN KEY (device_id) REFERENCES device_info (device_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ops_exception_device_id_fkey'
          AND conrelid = 'ops_exception'::regclass
    ) THEN
        ALTER TABLE ops_exception
            ADD CONSTRAINT ops_exception_device_id_fkey
            FOREIGN KEY (device_id) REFERENCES device_info (device_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ops_exception_session_id_fkey'
          AND conrelid = 'ops_exception'::regclass
    ) THEN
        ALTER TABLE ops_exception
            ADD CONSTRAINT ops_exception_session_id_fkey
            FOREIGN KEY (session_id) REFERENCES shopping_session (session_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ops_exception_order_id_fkey'
          AND conrelid = 'ops_exception'::regclass
    ) THEN
        ALTER TABLE ops_exception
            ADD CONSTRAINT ops_exception_order_id_fkey
            FOREIGN KEY (order_id) REFERENCES cabinet_order (order_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'user_feedback_device_id_fkey'
          AND conrelid = 'user_feedback'::regclass
    ) THEN
        ALTER TABLE user_feedback
            ADD CONSTRAINT user_feedback_device_id_fkey
            FOREIGN KEY (device_id) REFERENCES device_info (device_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'user_feedback_session_id_fkey'
          AND conrelid = 'user_feedback'::regclass
    ) THEN
        ALTER TABLE user_feedback
            ADD CONSTRAINT user_feedback_session_id_fkey
            FOREIGN KEY (session_id) REFERENCES shopping_session (session_id)
            ON DELETE SET NULL;
    END IF;
END $$;
