-- Enforce planogram ownership: every device_slot must belong to an existing device_info.
-- ON DELETE CASCADE so E2E / decommission hard-deletes cannot leave orphan slots again.

DELETE FROM device_slot s
WHERE NOT EXISTS (
    SELECT 1 FROM device_info d WHERE d.device_id = s.device_id
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'device_slot_device_id_fkey'
          AND conrelid = 'device_slot'::regclass
    ) THEN
        ALTER TABLE device_slot
            ADD CONSTRAINT device_slot_device_id_fkey
            FOREIGN KEY (device_id)
            REFERENCES device_info (device_id)
            ON DELETE CASCADE;
    END IF;
END $$;
