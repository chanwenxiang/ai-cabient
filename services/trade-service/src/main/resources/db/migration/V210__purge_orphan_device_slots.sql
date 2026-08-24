-- Remove planogram rows whose device_id no longer exists in device_info (E2E leftovers).
DELETE FROM device_slot s
WHERE NOT EXISTS (
    SELECT 1 FROM device_info d WHERE d.device_id = s.device_id
);
