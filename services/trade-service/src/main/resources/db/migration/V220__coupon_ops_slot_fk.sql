-- Coupon/promotion, ops reviewer/operator, repair chain, category, line slot validation.
-- Nullable refs SET NULL; creators/definitions RESTRICT; line slot checks via trigger.

UPDATE cabinet_order o
SET coupon_id = NULL
WHERE o.coupon_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_coupon c WHERE c.coupon_id = o.coupon_id);

UPDATE shopping_session s
SET coupon_id = NULL
WHERE s.coupon_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_coupon c WHERE c.coupon_id = s.coupon_id);

UPDATE shopping_session s
SET preferred_coupon_id = NULL
WHERE s.preferred_coupon_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_coupon c WHERE c.coupon_id = s.preferred_coupon_id);

UPDATE cabinet_order o
SET promotion_id = NULL
WHERE o.promotion_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM promotion_activity a WHERE a.activity_id = o.promotion_id);

UPDATE sku_catalog s
SET category_id = NULL
WHERE s.category_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM aliyun_category_mapping m WHERE m.category_id = s.category_id);

UPDATE merchant_replenishment_request r
SET reviewer_id = NULL
WHERE r.reviewer_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = r.reviewer_id);

UPDATE merchant_replenishment_request r
SET replenishment_task_id = NULL
WHERE r.replenishment_task_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM replenishment_task t WHERE t.task_id = r.replenishment_task_id);

UPDATE user_feedback f
SET handler_id = NULL
WHERE f.handler_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = f.handler_id);

UPDATE repair_ticket t
SET created_by = NULL
WHERE t.created_by IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = t.created_by);

UPDATE balance_refund_request r
SET reviewer_id = NULL
WHERE r.reviewer_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = r.reviewer_id);

UPDATE merchant_withdraw_request r
SET reviewer_id = NULL
WHERE r.reviewer_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = r.reviewer_id);

UPDATE line_withdraw_request r
SET reviewer_id = NULL
WHERE r.reviewer_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = r.reviewer_id);

UPDATE promotion_activity a
SET operator_id = NULL
WHERE a.operator_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = a.operator_id);

UPDATE inventory_movement m
SET operator_id = NULL
WHERE m.operator_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = m.operator_id);

UPDATE inventory_write_off w
SET operator_id = NULL
WHERE w.operator_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = w.operator_id);

UPDATE purchase_order o
SET operator_id = NULL
WHERE o.operator_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = o.operator_id);

UPDATE purchase_return r
SET operator_id = NULL
WHERE r.operator_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = r.operator_id);

UPDATE warehouse_inbound i
SET operator_id = NULL
WHERE i.operator_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = i.operator_id);

UPDATE warehouse_movement m
SET operator_id = NULL
WHERE m.operator_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = m.operator_id);

UPDATE warehouse_stocktake s
SET operator_id = NULL
WHERE s.operator_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = s.operator_id);

UPDATE warehouse_transfer_order o
SET operator_id = NULL
WHERE o.operator_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = o.operator_id);

UPDATE repair_ticket_event e
SET operator_id = NULL
WHERE e.operator_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = e.operator_id);

UPDATE device_lifecycle_event e
SET operator_id = NULL
WHERE e.operator_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = e.operator_id);

UPDATE announcement a
SET operator_id = NULL
WHERE a.operator_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = a.operator_id);

UPDATE supplier_payment p
SET operator_id = NULL
WHERE p.operator_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = p.operator_id);

UPDATE dispute_message m
SET author_id = NULL
WHERE m.author_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = m.author_id);

UPDATE merchant m
SET auditor_id = NULL
WHERE m.auditor_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = m.auditor_id);

UPDATE cabinet_order_line l
SET slot_id = NULL
WHERE l.slot_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM cabinet_order o
      JOIN device_slot s ON s.device_id = o.device_id AND s.slot_code = l.slot_id
      WHERE o.order_id = l.order_id
  );

UPDATE replenishment_task_line l
SET slot_id = NULL
WHERE l.slot_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM replenishment_task t
      JOIN device_slot s ON s.device_id = t.device_id AND s.slot_code = l.slot_id
      WHERE t.task_id = l.task_id
  );

DELETE FROM repair_ticket_event e
WHERE NOT EXISTS (SELECT 1 FROM repair_ticket t WHERE t.ticket_id = e.ticket_id);

DELETE FROM points_redeem_item i
WHERE NOT EXISTS (SELECT 1 FROM coupon_definition d WHERE d.coupon_def_id = i.coupon_def_id);

DELETE FROM merchant_replenishment_request r
WHERE NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = r.created_by);

CREATE OR REPLACE FUNCTION validate_cabinet_order_line_slot()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.slot_id IS NOT NULL AND NOT EXISTS (
        SELECT 1
        FROM cabinet_order o
        JOIN device_slot s ON s.device_id = o.device_id AND s.slot_code = NEW.slot_id
        WHERE o.order_id = NEW.order_id
    ) THEN
        RAISE EXCEPTION 'cabinet_order_line slot_id % not found on order device', NEW.slot_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION validate_replenishment_task_line_slot()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.slot_id IS NOT NULL AND NOT EXISTS (
        SELECT 1
        FROM replenishment_task t
        JOIN device_slot s ON s.device_id = t.device_id AND s.slot_code = NEW.slot_id
        WHERE t.task_id = NEW.task_id
    ) THEN
        RAISE EXCEPTION 'replenishment_task_line slot_id % not found on task device', NEW.slot_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_cabinet_order_line_slot ON cabinet_order_line;
CREATE TRIGGER trg_cabinet_order_line_slot
    BEFORE INSERT OR UPDATE OF slot_id, order_id ON cabinet_order_line
    FOR EACH ROW
    EXECUTE FUNCTION validate_cabinet_order_line_slot();

DROP TRIGGER IF EXISTS trg_replenishment_task_line_slot ON replenishment_task_line;
CREATE TRIGGER trg_replenishment_task_line_slot
    BEFORE INSERT OR UPDATE OF slot_id, task_id ON replenishment_task_line
    FOR EACH ROW
    EXECUTE FUNCTION validate_replenishment_task_line_slot();

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'cabinet_order_coupon_id_fkey'
          AND conrelid = 'cabinet_order'::regclass
    ) THEN
        ALTER TABLE cabinet_order
            ADD CONSTRAINT cabinet_order_coupon_id_fkey
            FOREIGN KEY (coupon_id) REFERENCES user_coupon (coupon_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'cabinet_order_promotion_id_fkey'
          AND conrelid = 'cabinet_order'::regclass
    ) THEN
        ALTER TABLE cabinet_order
            ADD CONSTRAINT cabinet_order_promotion_id_fkey
            FOREIGN KEY (promotion_id) REFERENCES promotion_activity (activity_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'shopping_session_coupon_id_fkey'
          AND conrelid = 'shopping_session'::regclass
    ) THEN
        ALTER TABLE shopping_session
            ADD CONSTRAINT shopping_session_coupon_id_fkey
            FOREIGN KEY (coupon_id) REFERENCES user_coupon (coupon_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'shopping_session_preferred_coupon_id_fkey'
          AND conrelid = 'shopping_session'::regclass
    ) THEN
        ALTER TABLE shopping_session
            ADD CONSTRAINT shopping_session_preferred_coupon_id_fkey
            FOREIGN KEY (preferred_coupon_id) REFERENCES user_coupon (coupon_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'points_redeem_item_coupon_def_id_fkey'
          AND conrelid = 'points_redeem_item'::regclass
    ) THEN
        ALTER TABLE points_redeem_item
            ADD CONSTRAINT points_redeem_item_coupon_def_id_fkey
            FOREIGN KEY (coupon_def_id) REFERENCES coupon_definition (coupon_def_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'sku_catalog_category_id_fkey'
          AND conrelid = 'sku_catalog'::regclass
    ) THEN
        ALTER TABLE sku_catalog
            ADD CONSTRAINT sku_catalog_category_id_fkey
            FOREIGN KEY (category_id) REFERENCES aliyun_category_mapping (category_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'merchant_replenishment_request_created_by_fkey'
          AND conrelid = 'merchant_replenishment_request'::regclass
    ) THEN
        ALTER TABLE merchant_replenishment_request
            ADD CONSTRAINT merchant_replenishment_request_created_by_fkey
            FOREIGN KEY (created_by) REFERENCES user_info (user_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'merchant_replenishment_request_reviewer_id_fkey'
          AND conrelid = 'merchant_replenishment_request'::regclass
    ) THEN
        ALTER TABLE merchant_replenishment_request
            ADD CONSTRAINT merchant_replenishment_request_reviewer_id_fkey
            FOREIGN KEY (reviewer_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'merchant_replenishment_request_replenishment_task_id_fkey'
          AND conrelid = 'merchant_replenishment_request'::regclass
    ) THEN
        ALTER TABLE merchant_replenishment_request
            ADD CONSTRAINT merchant_replenishment_request_replenishment_task_id_fkey
            FOREIGN KEY (replenishment_task_id) REFERENCES replenishment_task (task_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'user_feedback_handler_id_fkey'
          AND conrelid = 'user_feedback'::regclass
    ) THEN
        ALTER TABLE user_feedback
            ADD CONSTRAINT user_feedback_handler_id_fkey
            FOREIGN KEY (handler_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'repair_ticket_created_by_fkey'
          AND conrelid = 'repair_ticket'::regclass
    ) THEN
        ALTER TABLE repair_ticket
            ADD CONSTRAINT repair_ticket_created_by_fkey
            FOREIGN KEY (created_by) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'balance_refund_request_reviewer_id_fkey'
          AND conrelid = 'balance_refund_request'::regclass
    ) THEN
        ALTER TABLE balance_refund_request
            ADD CONSTRAINT balance_refund_request_reviewer_id_fkey
            FOREIGN KEY (reviewer_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'merchant_withdraw_request_reviewer_id_fkey'
          AND conrelid = 'merchant_withdraw_request'::regclass
    ) THEN
        ALTER TABLE merchant_withdraw_request
            ADD CONSTRAINT merchant_withdraw_request_reviewer_id_fkey
            FOREIGN KEY (reviewer_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'line_withdraw_request_reviewer_id_fkey'
          AND conrelid = 'line_withdraw_request'::regclass
    ) THEN
        ALTER TABLE line_withdraw_request
            ADD CONSTRAINT line_withdraw_request_reviewer_id_fkey
            FOREIGN KEY (reviewer_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'repair_ticket_event_ticket_id_fkey'
          AND conrelid = 'repair_ticket_event'::regclass
    ) THEN
        ALTER TABLE repair_ticket_event
            ADD CONSTRAINT repair_ticket_event_ticket_id_fkey
            FOREIGN KEY (ticket_id) REFERENCES repair_ticket (ticket_id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'promotion_activity_operator_id_fkey'
          AND conrelid = 'promotion_activity'::regclass
    ) THEN
        ALTER TABLE promotion_activity
            ADD CONSTRAINT promotion_activity_operator_id_fkey
            FOREIGN KEY (operator_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'inventory_movement_operator_id_fkey'
          AND conrelid = 'inventory_movement'::regclass
    ) THEN
        ALTER TABLE inventory_movement
            ADD CONSTRAINT inventory_movement_operator_id_fkey
            FOREIGN KEY (operator_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'inventory_write_off_operator_id_fkey'
          AND conrelid = 'inventory_write_off'::regclass
    ) THEN
        ALTER TABLE inventory_write_off
            ADD CONSTRAINT inventory_write_off_operator_id_fkey
            FOREIGN KEY (operator_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'purchase_order_operator_id_fkey'
          AND conrelid = 'purchase_order'::regclass
    ) THEN
        ALTER TABLE purchase_order
            ADD CONSTRAINT purchase_order_operator_id_fkey
            FOREIGN KEY (operator_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'purchase_return_operator_id_fkey'
          AND conrelid = 'purchase_return'::regclass
    ) THEN
        ALTER TABLE purchase_return
            ADD CONSTRAINT purchase_return_operator_id_fkey
            FOREIGN KEY (operator_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'warehouse_inbound_operator_id_fkey'
          AND conrelid = 'warehouse_inbound'::regclass
    ) THEN
        ALTER TABLE warehouse_inbound
            ADD CONSTRAINT warehouse_inbound_operator_id_fkey
            FOREIGN KEY (operator_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'warehouse_movement_operator_id_fkey'
          AND conrelid = 'warehouse_movement'::regclass
    ) THEN
        ALTER TABLE warehouse_movement
            ADD CONSTRAINT warehouse_movement_operator_id_fkey
            FOREIGN KEY (operator_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'warehouse_stocktake_operator_id_fkey'
          AND conrelid = 'warehouse_stocktake'::regclass
    ) THEN
        ALTER TABLE warehouse_stocktake
            ADD CONSTRAINT warehouse_stocktake_operator_id_fkey
            FOREIGN KEY (operator_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'warehouse_transfer_order_operator_id_fkey'
          AND conrelid = 'warehouse_transfer_order'::regclass
    ) THEN
        ALTER TABLE warehouse_transfer_order
            ADD CONSTRAINT warehouse_transfer_order_operator_id_fkey
            FOREIGN KEY (operator_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'repair_ticket_event_operator_id_fkey'
          AND conrelid = 'repair_ticket_event'::regclass
    ) THEN
        ALTER TABLE repair_ticket_event
            ADD CONSTRAINT repair_ticket_event_operator_id_fkey
            FOREIGN KEY (operator_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'device_lifecycle_event_operator_id_fkey'
          AND conrelid = 'device_lifecycle_event'::regclass
    ) THEN
        ALTER TABLE device_lifecycle_event
            ADD CONSTRAINT device_lifecycle_event_operator_id_fkey
            FOREIGN KEY (operator_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'announcement_operator_id_fkey'
          AND conrelid = 'announcement'::regclass
    ) THEN
        ALTER TABLE announcement
            ADD CONSTRAINT announcement_operator_id_fkey
            FOREIGN KEY (operator_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'supplier_payment_operator_id_fkey'
          AND conrelid = 'supplier_payment'::regclass
    ) THEN
        ALTER TABLE supplier_payment
            ADD CONSTRAINT supplier_payment_operator_id_fkey
            FOREIGN KEY (operator_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'dispute_message_author_id_fkey'
          AND conrelid = 'dispute_message'::regclass
    ) THEN
        ALTER TABLE dispute_message
            ADD CONSTRAINT dispute_message_author_id_fkey
            FOREIGN KEY (author_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'merchant_auditor_id_fkey'
          AND conrelid = 'merchant'::regclass
    ) THEN
        ALTER TABLE merchant
            ADD CONSTRAINT merchant_auditor_id_fkey
            FOREIGN KEY (auditor_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;
END $$;
