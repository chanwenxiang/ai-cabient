-- Device cabinet inventory must reference device_info + sku_catalog.
-- device_id CASCADE (remove lots/summary when device decommissioned);
-- sku_id RESTRICT (block SKU delete while still stocked on a device).

DELETE FROM device_sku_lot l
WHERE NOT EXISTS (SELECT 1 FROM device_info d WHERE d.device_id = l.device_id)
   OR NOT EXISTS (SELECT 1 FROM sku_catalog s WHERE s.sku_id = l.sku_id);

DELETE FROM device_sku_inventory i
WHERE NOT EXISTS (SELECT 1 FROM device_info d WHERE d.device_id = i.device_id)
   OR NOT EXISTS (SELECT 1 FROM sku_catalog s WHERE s.sku_id = i.sku_id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'device_sku_lot_device_id_fkey'
          AND conrelid = 'device_sku_lot'::regclass
    ) THEN
        ALTER TABLE device_sku_lot
            ADD CONSTRAINT device_sku_lot_device_id_fkey
            FOREIGN KEY (device_id) REFERENCES device_info (device_id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'device_sku_lot_sku_id_fkey'
          AND conrelid = 'device_sku_lot'::regclass
    ) THEN
        ALTER TABLE device_sku_lot
            ADD CONSTRAINT device_sku_lot_sku_id_fkey
            FOREIGN KEY (sku_id) REFERENCES sku_catalog (sku_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'device_sku_inventory_device_id_fkey'
          AND conrelid = 'device_sku_inventory'::regclass
    ) THEN
        ALTER TABLE device_sku_inventory
            ADD CONSTRAINT device_sku_inventory_device_id_fkey
            FOREIGN KEY (device_id) REFERENCES device_info (device_id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'device_sku_inventory_sku_id_fkey'
          AND conrelid = 'device_sku_inventory'::regclass
    ) THEN
        ALTER TABLE device_sku_inventory
            ADD CONSTRAINT device_sku_inventory_sku_id_fkey
            FOREIGN KEY (sku_id) REFERENCES sku_catalog (sku_id)
            ON DELETE RESTRICT;
    END IF;
END $$;
