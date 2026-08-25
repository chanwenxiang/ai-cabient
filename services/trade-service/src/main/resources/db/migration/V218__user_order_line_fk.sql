-- User / order-line links to user_info and sku_catalog.
-- Junction & prefs CASCADE; financial/payment RESTRICT; nullable audit SET NULL.
-- Order / procurement lines: sku_id RESTRICT (preserve historical line integrity).

UPDATE device_fault_report r
SET user_id = NULL
WHERE r.user_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = r.user_id);

UPDATE line_manager m
SET user_id = NULL
WHERE m.user_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = m.user_id);

UPDATE notification_log l
SET user_id = NULL
WHERE l.user_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = l.user_id);

UPDATE payment_operation o
SET user_id = NULL
WHERE o.user_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = o.user_id);

UPDATE phone_verify_log l
SET user_id = NULL
WHERE l.user_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = l.user_id);

UPDATE user_login_log l
SET user_id = NULL
WHERE l.user_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = l.user_id);

DELETE FROM member m
WHERE NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = m.user_id);

DELETE FROM user_blacklist b
WHERE NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = b.user_id);

DELETE FROM user_notify_pref p
WHERE NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = p.user_id);

DELETE FROM ops_user_merchant m
WHERE NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = m.user_id);

DELETE FROM balance_refund_request r
WHERE NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = r.user_id);

DELETE FROM invoice_request r
WHERE NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = r.user_id);

DELETE FROM merchant_notify_log l
WHERE NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = l.user_id);

DELETE FROM payscore_order o
WHERE NOT EXISTS (SELECT 1 FROM user_info u WHERE u.user_id = o.user_id);

DELETE FROM cabinet_order_line l
WHERE NOT EXISTS (SELECT 1 FROM sku_catalog s WHERE s.sku_id = l.sku_id);

DELETE FROM merchant_replenishment_request_line l
WHERE NOT EXISTS (SELECT 1 FROM sku_catalog s WHERE s.sku_id = l.sku_id);

DELETE FROM purchase_order_line l
WHERE NOT EXISTS (SELECT 1 FROM sku_catalog s WHERE s.sku_id = l.sku_id);

DELETE FROM replenishment_task_line l
WHERE NOT EXISTS (SELECT 1 FROM sku_catalog s WHERE s.sku_id = l.sku_id);

DELETE FROM sku_delist_review r
WHERE NOT EXISTS (SELECT 1 FROM sku_catalog s WHERE s.sku_id = r.sku_id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'member_user_id_fkey'
          AND conrelid = 'member'::regclass
    ) THEN
        ALTER TABLE member
            ADD CONSTRAINT member_user_id_fkey
            FOREIGN KEY (user_id) REFERENCES user_info (user_id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'user_blacklist_user_id_fkey'
          AND conrelid = 'user_blacklist'::regclass
    ) THEN
        ALTER TABLE user_blacklist
            ADD CONSTRAINT user_blacklist_user_id_fkey
            FOREIGN KEY (user_id) REFERENCES user_info (user_id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'user_notify_pref_user_id_fkey'
          AND conrelid = 'user_notify_pref'::regclass
    ) THEN
        ALTER TABLE user_notify_pref
            ADD CONSTRAINT user_notify_pref_user_id_fkey
            FOREIGN KEY (user_id) REFERENCES user_info (user_id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ops_user_merchant_user_id_fkey'
          AND conrelid = 'ops_user_merchant'::regclass
    ) THEN
        ALTER TABLE ops_user_merchant
            ADD CONSTRAINT ops_user_merchant_user_id_fkey
            FOREIGN KEY (user_id) REFERENCES user_info (user_id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'line_manager_user_id_fkey'
          AND conrelid = 'line_manager'::regclass
    ) THEN
        ALTER TABLE line_manager
            ADD CONSTRAINT line_manager_user_id_fkey
            FOREIGN KEY (user_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'device_fault_report_user_id_fkey'
          AND conrelid = 'device_fault_report'::regclass
    ) THEN
        ALTER TABLE device_fault_report
            ADD CONSTRAINT device_fault_report_user_id_fkey
            FOREIGN KEY (user_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'notification_log_user_id_fkey'
          AND conrelid = 'notification_log'::regclass
    ) THEN
        ALTER TABLE notification_log
            ADD CONSTRAINT notification_log_user_id_fkey
            FOREIGN KEY (user_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'payment_operation_user_id_fkey'
          AND conrelid = 'payment_operation'::regclass
    ) THEN
        ALTER TABLE payment_operation
            ADD CONSTRAINT payment_operation_user_id_fkey
            FOREIGN KEY (user_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'phone_verify_log_user_id_fkey'
          AND conrelid = 'phone_verify_log'::regclass
    ) THEN
        ALTER TABLE phone_verify_log
            ADD CONSTRAINT phone_verify_log_user_id_fkey
            FOREIGN KEY (user_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'user_login_log_user_id_fkey'
          AND conrelid = 'user_login_log'::regclass
    ) THEN
        ALTER TABLE user_login_log
            ADD CONSTRAINT user_login_log_user_id_fkey
            FOREIGN KEY (user_id) REFERENCES user_info (user_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'balance_refund_request_user_id_fkey'
          AND conrelid = 'balance_refund_request'::regclass
    ) THEN
        ALTER TABLE balance_refund_request
            ADD CONSTRAINT balance_refund_request_user_id_fkey
            FOREIGN KEY (user_id) REFERENCES user_info (user_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'invoice_request_user_id_fkey'
          AND conrelid = 'invoice_request'::regclass
    ) THEN
        ALTER TABLE invoice_request
            ADD CONSTRAINT invoice_request_user_id_fkey
            FOREIGN KEY (user_id) REFERENCES user_info (user_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'merchant_notify_log_user_id_fkey'
          AND conrelid = 'merchant_notify_log'::regclass
    ) THEN
        ALTER TABLE merchant_notify_log
            ADD CONSTRAINT merchant_notify_log_user_id_fkey
            FOREIGN KEY (user_id) REFERENCES user_info (user_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'payscore_order_user_id_fkey'
          AND conrelid = 'payscore_order'::regclass
    ) THEN
        ALTER TABLE payscore_order
            ADD CONSTRAINT payscore_order_user_id_fkey
            FOREIGN KEY (user_id) REFERENCES user_info (user_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'cabinet_order_line_sku_id_fkey'
          AND conrelid = 'cabinet_order_line'::regclass
    ) THEN
        ALTER TABLE cabinet_order_line
            ADD CONSTRAINT cabinet_order_line_sku_id_fkey
            FOREIGN KEY (sku_id) REFERENCES sku_catalog (sku_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'merchant_replenishment_request_line_sku_id_fkey'
          AND conrelid = 'merchant_replenishment_request_line'::regclass
    ) THEN
        ALTER TABLE merchant_replenishment_request_line
            ADD CONSTRAINT merchant_replenishment_request_line_sku_id_fkey
            FOREIGN KEY (sku_id) REFERENCES sku_catalog (sku_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'purchase_order_line_sku_id_fkey'
          AND conrelid = 'purchase_order_line'::regclass
    ) THEN
        ALTER TABLE purchase_order_line
            ADD CONSTRAINT purchase_order_line_sku_id_fkey
            FOREIGN KEY (sku_id) REFERENCES sku_catalog (sku_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'replenishment_task_line_sku_id_fkey'
          AND conrelid = 'replenishment_task_line'::regclass
    ) THEN
        ALTER TABLE replenishment_task_line
            ADD CONSTRAINT replenishment_task_line_sku_id_fkey
            FOREIGN KEY (sku_id) REFERENCES sku_catalog (sku_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'sku_delist_review_sku_id_fkey'
          AND conrelid = 'sku_delist_review'::regclass
    ) THEN
        ALTER TABLE sku_delist_review
            ADD CONSTRAINT sku_delist_review_sku_id_fkey
            FOREIGN KEY (sku_id) REFERENCES sku_catalog (sku_id)
            ON DELETE RESTRICT;
    END IF;
END $$;
