-- Merchant logs, order cross-refs, composite slot binding, replenishment/outbound chain.
-- Nullable refs SET NULL; invoice/revenue RESTRICT; slot binding RESTRICT when stocked.

UPDATE notification_log l
SET merchant_id = NULL
WHERE l.merchant_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM merchant m WHERE m.merchant_id = l.merchant_id);

UPDATE phone_verify_log l
SET merchant_id = NULL
WHERE l.merchant_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM merchant m WHERE m.merchant_id = l.merchant_id);

UPDATE shopping_session s
SET order_id = NULL
WHERE s.order_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM cabinet_order o WHERE o.order_id = s.order_id);

UPDATE replenishment_task t
SET outbound_id = NULL
WHERE t.outbound_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM warehouse_outbound o WHERE o.outbound_id = t.outbound_id);

UPDATE replenishment_task t
SET request_id = NULL
WHERE t.request_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM merchant_replenishment_request r WHERE r.request_id = t.request_id);

UPDATE merchant_replenishment_request r
SET outbound_id = NULL
WHERE r.outbound_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM warehouse_outbound o WHERE o.outbound_id = r.outbound_id);

UPDATE shopping_session s
SET replenishment_task_id = NULL
WHERE s.replenishment_task_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM replenishment_task t WHERE t.task_id = s.replenishment_task_id);

UPDATE replenishment_task t
SET assignee_user_id = NULL
WHERE t.assignee_user_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = t.assignee_user_id);

UPDATE replenishment_route r
SET assignee_user_id = NULL
WHERE r.assignee_user_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = r.assignee_user_id);

UPDATE warehouse_outbound o
SET assignee_user_id = NULL
WHERE o.assignee_user_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = o.assignee_user_id);

UPDATE ops_exception e
SET assignee_user_id = NULL
WHERE e.assignee_user_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = e.assignee_user_id);

UPDATE device_sku_lot l
SET slot_id = NULL
WHERE l.slot_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM device_slot s
      WHERE s.device_id = l.device_id AND s.slot_code = l.slot_id
  );

UPDATE warehouse_outbound_line l
SET slot_id = NULL
WHERE l.slot_id IS NOT NULL
  AND (
      l.device_id IS NULL
      OR NOT EXISTS (
          SELECT 1 FROM device_slot s
          WHERE s.device_id = l.device_id AND s.slot_code = l.slot_id
      )
  );

DELETE FROM invoice_request r
WHERE NOT EXISTS (SELECT 1 FROM cabinet_order o WHERE o.order_id = r.order_id);

DELETE FROM revenue_share_detail d
WHERE NOT EXISTS (SELECT 1 FROM cabinet_order o WHERE o.order_id = d.order_id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'notification_log_merchant_id_fkey'
          AND conrelid = 'notification_log'::regclass
    ) THEN
        ALTER TABLE notification_log
            ADD CONSTRAINT notification_log_merchant_id_fkey
            FOREIGN KEY (merchant_id) REFERENCES merchant (merchant_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'phone_verify_log_merchant_id_fkey'
          AND conrelid = 'phone_verify_log'::regclass
    ) THEN
        ALTER TABLE phone_verify_log
            ADD CONSTRAINT phone_verify_log_merchant_id_fkey
            FOREIGN KEY (merchant_id) REFERENCES merchant (merchant_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'invoice_request_order_id_fkey'
          AND conrelid = 'invoice_request'::regclass
    ) THEN
        ALTER TABLE invoice_request
            ADD CONSTRAINT invoice_request_order_id_fkey
            FOREIGN KEY (order_id) REFERENCES cabinet_order (order_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'revenue_share_detail_order_id_fkey'
          AND conrelid = 'revenue_share_detail'::regclass
    ) THEN
        ALTER TABLE revenue_share_detail
            ADD CONSTRAINT revenue_share_detail_order_id_fkey
            FOREIGN KEY (order_id) REFERENCES cabinet_order (order_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'shopping_session_order_id_fkey'
          AND conrelid = 'shopping_session'::regclass
    ) THEN
        ALTER TABLE shopping_session
            ADD CONSTRAINT shopping_session_order_id_fkey
            FOREIGN KEY (order_id) REFERENCES cabinet_order (order_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'replenishment_task_outbound_id_fkey'
          AND conrelid = 'replenishment_task'::regclass
    ) THEN
        ALTER TABLE replenishment_task
            ADD CONSTRAINT replenishment_task_outbound_id_fkey
            FOREIGN KEY (outbound_id) REFERENCES warehouse_outbound (outbound_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'replenishment_task_request_id_fkey'
          AND conrelid = 'replenishment_task'::regclass
    ) THEN
        ALTER TABLE replenishment_task
            ADD CONSTRAINT replenishment_task_request_id_fkey
            FOREIGN KEY (request_id) REFERENCES merchant_replenishment_request (request_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'merchant_replenishment_request_outbound_id_fkey'
          AND conrelid = 'merchant_replenishment_request'::regclass
    ) THEN
        ALTER TABLE merchant_replenishment_request
            ADD CONSTRAINT merchant_replenishment_request_outbound_id_fkey
            FOREIGN KEY (outbound_id) REFERENCES warehouse_outbound (outbound_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'shopping_session_replenishment_task_id_fkey'
          AND conrelid = 'shopping_session'::regclass
    ) THEN
        ALTER TABLE shopping_session
            ADD CONSTRAINT shopping_session_replenishment_task_id_fkey
            FOREIGN KEY (replenishment_task_id) REFERENCES replenishment_task (task_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'replenishment_task_assignee_user_id_fkey'
          AND conrelid = 'replenishment_task'::regclass
    ) THEN
        ALTER TABLE replenishment_task
            ADD CONSTRAINT replenishment_task_assignee_user_id_fkey
            FOREIGN KEY (assignee_user_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'replenishment_route_assignee_user_id_fkey'
          AND conrelid = 'replenishment_route'::regclass
    ) THEN
        ALTER TABLE replenishment_route
            ADD CONSTRAINT replenishment_route_assignee_user_id_fkey
            FOREIGN KEY (assignee_user_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'warehouse_outbound_assignee_user_id_fkey'
          AND conrelid = 'warehouse_outbound'::regclass
    ) THEN
        ALTER TABLE warehouse_outbound
            ADD CONSTRAINT warehouse_outbound_assignee_user_id_fkey
            FOREIGN KEY (assignee_user_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ops_exception_assignee_user_id_fkey'
          AND conrelid = 'ops_exception'::regclass
    ) THEN
        ALTER TABLE ops_exception
            ADD CONSTRAINT ops_exception_assignee_user_id_fkey
            FOREIGN KEY (assignee_user_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'device_sku_lot_device_slot_fkey'
          AND conrelid = 'device_sku_lot'::regclass
    ) THEN
        ALTER TABLE device_sku_lot
            ADD CONSTRAINT device_sku_lot_device_slot_fkey
            FOREIGN KEY (device_id, slot_id) REFERENCES device_slot (device_id, slot_code)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'warehouse_outbound_line_device_slot_fkey'
          AND conrelid = 'warehouse_outbound_line'::regclass
    ) THEN
        ALTER TABLE warehouse_outbound_line
            ADD CONSTRAINT warehouse_outbound_line_device_slot_fkey
            FOREIGN KEY (device_id, slot_id) REFERENCES device_slot (device_id, slot_code)
            ON DELETE RESTRICT;
    END IF;
END $$;
