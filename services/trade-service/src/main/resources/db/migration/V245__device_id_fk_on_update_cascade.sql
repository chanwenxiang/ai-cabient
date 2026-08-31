-- 支持 device_info 主键原地更换：子表 FK 级联 ON UPDATE CASCADE。

DO $$
DECLARE
    r RECORD;
    del_action text;
BEGIN
    FOR r IN
        SELECT c.conname,
               c.conrelid::regclass::text AS child_table,
               c.confdeltype
        FROM pg_constraint c
        WHERE c.contype = 'f'
          AND c.confrelid = 'device_info'::regclass
          AND pg_get_constraintdef(c.oid) NOT LIKE '%ON UPDATE CASCADE%'
    LOOP
        del_action := CASE r.confdeltype
            WHEN 'a' THEN 'NO ACTION'
            WHEN 'r' THEN 'RESTRICT'
            WHEN 'c' THEN 'CASCADE'
            WHEN 'n' THEN 'SET NULL'
            WHEN 'd' THEN 'SET DEFAULT'
            ELSE 'NO ACTION'
        END;
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.child_table, r.conname);
        EXECUTE format(
            'ALTER TABLE %s ADD CONSTRAINT %I FOREIGN KEY (device_id) REFERENCES device_info(device_id) ON DELETE %s ON UPDATE CASCADE',
            r.child_table, r.conname, del_action
        );
    END LOOP;
END $$;

DO $$
DECLARE
    r RECORD;
    del_action text;
BEGIN
    FOR r IN
        SELECT c.conname,
               c.conrelid::regclass::text AS child_table,
               c.confdeltype,
               pg_get_constraintdef(c.oid) AS def
        FROM pg_constraint c
        WHERE c.contype = 'f'
          AND c.confrelid = 'device_slot'::regclass
          AND pg_get_constraintdef(c.oid) NOT LIKE '%ON UPDATE CASCADE%'
    LOOP
        del_action := CASE r.confdeltype
            WHEN 'a' THEN 'NO ACTION'
            WHEN 'r' THEN 'RESTRICT'
            WHEN 'c' THEN 'CASCADE'
            WHEN 'n' THEN 'SET NULL'
            WHEN 'd' THEN 'SET DEFAULT'
            ELSE 'NO ACTION'
        END;
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.child_table, r.conname);
        IF r.conname = 'device_sku_lot_device_slot_fkey' THEN
            EXECUTE format(
                'ALTER TABLE %s ADD CONSTRAINT %I FOREIGN KEY (device_id, slot_id) REFERENCES device_slot(device_id, slot_code) ON DELETE %s ON UPDATE CASCADE',
                r.child_table, r.conname, del_action
            );
        ELSIF r.conname = 'warehouse_outbound_line_device_slot_fkey' THEN
            EXECUTE format(
                'ALTER TABLE %s ADD CONSTRAINT %I FOREIGN KEY (device_id, slot_id) REFERENCES device_slot(device_id, slot_code) ON DELETE %s ON UPDATE CASCADE',
                r.child_table, r.conname, del_action
            );
        ELSE
            RAISE EXCEPTION 'unexpected device_slot FK: %', r.conname;
        END IF;
    END LOOP;
END $$;
