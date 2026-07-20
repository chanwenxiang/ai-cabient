-- 出库明细带货道：规划拆行时写入，运营后台展开可见
ALTER TABLE warehouse_outbound_line
    ADD COLUMN IF NOT EXISTS slot_id VARCHAR(32);

CREATE INDEX IF NOT EXISTS idx_wh_outbound_line_slot
    ON warehouse_outbound_line (device_id, slot_id)
    WHERE slot_id IS NOT NULL;
