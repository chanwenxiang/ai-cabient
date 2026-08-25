-- Warehouse inventory/lines must reference warehouse + sku_catalog.
-- sku_id RESTRICT (block SKU delete while stocked); warehouse links RESTRICT for audit trails.

DELETE FROM warehouse_transfer_line l
WHERE NOT EXISTS (SELECT 1 FROM sku_catalog s WHERE s.sku_id = l.sku_id);

DELETE FROM warehouse_stocktake_line l
WHERE NOT EXISTS (SELECT 1 FROM sku_catalog s WHERE s.sku_id = l.sku_id);

DELETE FROM warehouse_outbound_line l
WHERE NOT EXISTS (SELECT 1 FROM sku_catalog s WHERE s.sku_id = l.sku_id);

DELETE FROM warehouse_inbound_line l
WHERE NOT EXISTS (SELECT 1 FROM sku_catalog s WHERE s.sku_id = l.sku_id);

DELETE FROM warehouse_bin_stock s
WHERE NOT EXISTS (SELECT 1 FROM sku_catalog c WHERE c.sku_id = s.sku_id);

DELETE FROM warehouse_in_transit t
WHERE NOT EXISTS (SELECT 1 FROM sku_catalog s WHERE s.sku_id = t.sku_id);

DELETE FROM warehouse_movement m
WHERE NOT EXISTS (SELECT 1 FROM sku_catalog s WHERE s.sku_id = m.sku_id);

DELETE FROM warehouse_inventory i
WHERE NOT EXISTS (SELECT 1 FROM warehouse w WHERE w.warehouse_id = i.warehouse_id)
   OR NOT EXISTS (SELECT 1 FROM sku_catalog s WHERE s.sku_id = i.sku_id);

DELETE FROM purchase_return_line l
WHERE NOT EXISTS (SELECT 1 FROM sku_catalog s WHERE s.sku_id = l.sku_id);

DELETE FROM purchase_return r
WHERE NOT EXISTS (SELECT 1 FROM warehouse w WHERE w.warehouse_id = r.warehouse_id)
   OR NOT EXISTS (SELECT 1 FROM supplier s WHERE s.supplier_id = r.supplier_id);

DELETE FROM warehouse_transfer_order t
WHERE NOT EXISTS (SELECT 1 FROM warehouse w WHERE w.warehouse_id = t.from_warehouse_id)
   OR NOT EXISTS (SELECT 1 FROM warehouse w WHERE w.warehouse_id = t.to_warehouse_id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'warehouse_inventory_sku_id_fkey'
          AND conrelid = 'warehouse_inventory'::regclass
    ) THEN
        ALTER TABLE warehouse_inventory
            ADD CONSTRAINT warehouse_inventory_sku_id_fkey
            FOREIGN KEY (sku_id) REFERENCES sku_catalog (sku_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'warehouse_bin_stock_sku_id_fkey'
          AND conrelid = 'warehouse_bin_stock'::regclass
    ) THEN
        ALTER TABLE warehouse_bin_stock
            ADD CONSTRAINT warehouse_bin_stock_sku_id_fkey
            FOREIGN KEY (sku_id) REFERENCES sku_catalog (sku_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'warehouse_inbound_line_sku_id_fkey'
          AND conrelid = 'warehouse_inbound_line'::regclass
    ) THEN
        ALTER TABLE warehouse_inbound_line
            ADD CONSTRAINT warehouse_inbound_line_sku_id_fkey
            FOREIGN KEY (sku_id) REFERENCES sku_catalog (sku_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'warehouse_outbound_line_sku_id_fkey'
          AND conrelid = 'warehouse_outbound_line'::regclass
    ) THEN
        ALTER TABLE warehouse_outbound_line
            ADD CONSTRAINT warehouse_outbound_line_sku_id_fkey
            FOREIGN KEY (sku_id) REFERENCES sku_catalog (sku_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'warehouse_movement_sku_id_fkey'
          AND conrelid = 'warehouse_movement'::regclass
    ) THEN
        ALTER TABLE warehouse_movement
            ADD CONSTRAINT warehouse_movement_sku_id_fkey
            FOREIGN KEY (sku_id) REFERENCES sku_catalog (sku_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'warehouse_in_transit_sku_id_fkey'
          AND conrelid = 'warehouse_in_transit'::regclass
    ) THEN
        ALTER TABLE warehouse_in_transit
            ADD CONSTRAINT warehouse_in_transit_sku_id_fkey
            FOREIGN KEY (sku_id) REFERENCES sku_catalog (sku_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'warehouse_stocktake_line_sku_id_fkey'
          AND conrelid = 'warehouse_stocktake_line'::regclass
    ) THEN
        ALTER TABLE warehouse_stocktake_line
            ADD CONSTRAINT warehouse_stocktake_line_sku_id_fkey
            FOREIGN KEY (sku_id) REFERENCES sku_catalog (sku_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'warehouse_transfer_line_sku_id_fkey'
          AND conrelid = 'warehouse_transfer_line'::regclass
    ) THEN
        ALTER TABLE warehouse_transfer_line
            ADD CONSTRAINT warehouse_transfer_line_sku_id_fkey
            FOREIGN KEY (sku_id) REFERENCES sku_catalog (sku_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'purchase_return_line_sku_id_fkey'
          AND conrelid = 'purchase_return_line'::regclass
    ) THEN
        ALTER TABLE purchase_return_line
            ADD CONSTRAINT purchase_return_line_sku_id_fkey
            FOREIGN KEY (sku_id) REFERENCES sku_catalog (sku_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'purchase_return_warehouse_id_fkey'
          AND conrelid = 'purchase_return'::regclass
    ) THEN
        ALTER TABLE purchase_return
            ADD CONSTRAINT purchase_return_warehouse_id_fkey
            FOREIGN KEY (warehouse_id) REFERENCES warehouse (warehouse_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'purchase_return_supplier_id_fkey'
          AND conrelid = 'purchase_return'::regclass
    ) THEN
        ALTER TABLE purchase_return
            ADD CONSTRAINT purchase_return_supplier_id_fkey
            FOREIGN KEY (supplier_id) REFERENCES supplier (supplier_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'warehouse_transfer_order_from_wh_fkey'
          AND conrelid = 'warehouse_transfer_order'::regclass
    ) THEN
        ALTER TABLE warehouse_transfer_order
            ADD CONSTRAINT warehouse_transfer_order_from_wh_fkey
            FOREIGN KEY (from_warehouse_id) REFERENCES warehouse (warehouse_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'warehouse_transfer_order_to_wh_fkey'
          AND conrelid = 'warehouse_transfer_order'::regclass
    ) THEN
        ALTER TABLE warehouse_transfer_order
            ADD CONSTRAINT warehouse_transfer_order_to_wh_fkey
            FOREIGN KEY (to_warehouse_id) REFERENCES warehouse (warehouse_id)
            ON DELETE RESTRICT;
    END IF;
END $$;
