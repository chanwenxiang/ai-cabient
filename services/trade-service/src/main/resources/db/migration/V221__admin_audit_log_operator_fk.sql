-- System operator account for automated audit (Java uses operatorId=0L).
-- Reassign orphaned operator IDs; then enforce admin_audit_log.operator_id FK.

INSERT INTO user_info (user_id, phone_number, name, verified, status)
VALUES (0, '00000000000', '系统', false, 'ACTIVE')
ON CONFLICT (user_id) DO NOTHING;

UPDATE admin_audit_log
SET operator_id = 100000001
WHERE operator_id NOT IN (SELECT user_id FROM user_info);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'admin_audit_log_operator_id_fkey'
          AND conrelid = 'admin_audit_log'::regclass
    ) THEN
        ALTER TABLE admin_audit_log
            ADD CONSTRAINT admin_audit_log_operator_id_fkey
            FOREIGN KEY (operator_id) REFERENCES user_info (user_id)
            ON DELETE RESTRICT;
    END IF;
END $$;
