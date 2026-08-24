-- Remaining operational links: planogram SKU binding, in-transit/outbound, pull-off, ops/repair.
-- Nullable refs SET NULL; active operational rows RESTRICT; line binding CASCADE with device.

UPDATE device_slot s
SET assigned_sku_id = NULL
WHERE s.assigned_sku_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sku_catalog c WHERE c.sku_id = s.assigned_sku_id);

UPDATE warehouse_outbound_line l
SET device_id = NULL
WHERE l.device_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM device_info d WHERE d.device_id = l.device_id);

DELETE FROM warehouse_in_transit t
WHERE NOT EXISTS (SELECT 1 FROM device_info d WHERE d.device_id = t.device_id);

DELETE FROM pull_off_task t
WHERE NOT EXISTS (SELECT 1 FROM device_info d WHERE d.device_id = t.device_id)
   OR NOT EXISTS (SELECT 1 FROM sku_catalog s WHERE s.sku_id = t.sku_id);

DELETE FROM device_fault_report r
WHERE NOT EXISTS (SELECT 1 FROM device_info d WHERE d.device_id = r.device_id);

DELETE FROM device_ops_event e
WHERE NOT EXISTS (SELECT 1 FROM device_info d WHERE d.device_id = e.device_id);

DELETE FROM repair_ticket t
WHERE NOT EXISTS (SELECT 1 FROM device_info d WHERE d.device_id = t.device_id);

DELETE FROM line_device ld
WHERE NOT EXISTS (SELECT 1 FROM device_info d WHERE d.device_id = ld.device_id);

DELETE FROM inventory_write_off w
WHERE NOT EXISTS (SELECT 1 FROM device_info d WHERE d.device_id = w.device_id)
   OR NOT EXISTS (SELECT 1 FROM sku_catalog s WHERE s.sku_id = w.sku_id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'device_slot_assigned_sku_id_fkey'
          AND conrelid = 'device_slot'::regclass
    ) THEN
        ALTER TABLE device_slot
            ADD CONSTRAINT device_slot_assigned_sku_id_fkey
            FOREIGN KEY (assigned_sku_id) REFERENCES sku_catalog (sku_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'warehouse_in_transit_device_id_fkey'
          AND conrelid = 'warehouse_in_transit'::regclass
    ) THEN
        ALTER TABLE warehouse_in_transit
            ADD CONSTRAINT warehouse_in_transit_device_id_fkey
            FOREIGN KEY (device_id) REFERENCES device_info (device_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'warehouse_outbound_line_device_id_fkey'
          AND conrelid = 'warehouse_outbound_line'::regclass
    ) THEN
        ALTER TABLE warehouse_outbound_line
            ADD CONSTRAINT warehouse_outbound_line_device_id_fkey
            FOREIGN KEY (device_id) REFERENCES device_info (device_id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'pull_off_task_device_id_fkey'
          AND conrelid = 'pull_off_task'::regclass
    ) THEN
        ALTER TABLE pull_off_task
            ADD CONSTRAINT pull_off_task_device_id_fkey
            FOREIGN KEY (device_id) REFERENCES device_info (device_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'pull_off_task_sku_id_fkey'
          AND conrelid = 'pull_off_task'::regclass
    ) THEN
        ALTER TABLE pull_off_task
            ADD CONSTRAINT pull_off_task_sku_id_fkey
            FOREIGN KEY (sku_id) REFERENCES sku_catalog (sku_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'device_fault_report_device_id_fkey'
          AND conrelid = 'device_fault_report'::regclass
    ) THEN
        ALTER TABLE device_fault_report
            ADD CONSTRAINT device_fault_report_device_id_fkey
            FOREIGN KEY (device_id) REFERENCES device_info (device_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'device_ops_event_device_id_fkey'
          AND conrelid = 'device_ops_event'::regclass
    ) THEN
        ALTER TABLE device_ops_event
            ADD CONSTRAINT device_ops_event_device_id_fkey
            FOREIGN KEY (device_id) REFERENCES device_info (device_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'repair_ticket_device_id_fkey'
          AND conrelid = 'repair_ticket'::regclass
    ) THEN
        ALTER TABLE repair_ticket
            ADD CONSTRAINT repair_ticket_device_id_fkey
            FOREIGN KEY (device_id) REFERENCES device_info (device_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'line_device_device_id_fkey'
          AND conrelid = 'line_device'::regclass
    ) THEN
        ALTER TABLE line_device
            ADD CONSTRAINT line_device_device_id_fkey
            FOREIGN KEY (device_id) REFERENCES device_info (device_id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'inventory_write_off_device_id_fkey'
          AND conrelid = 'inventory_write_off'::regclass
    ) THEN
        ALTER TABLE inventory_write_off
            ADD CONSTRAINT inventory_write_off_device_id_fkey
            FOREIGN KEY (device_id) REFERENCES device_info (device_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'inventory_write_off_sku_id_fkey'
          AND conrelid = 'inventory_write_off'::regclass
    ) THEN
        ALTER TABLE inventory_write_off
            ADD CONSTRAINT inventory_write_off_sku_id_fkey
            FOREIGN KEY (sku_id) REFERENCES sku_catalog (sku_id)
            ON DELETE RESTRICT;
    END IF;
END $$;
