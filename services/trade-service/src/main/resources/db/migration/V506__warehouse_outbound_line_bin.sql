-- MA-413：出库行保留规划时的货位分配，发运扣货位、取消回仓恢复。
ALTER TABLE warehouse_outbound_line
    ADD COLUMN IF NOT EXISTS bin_id BIGINT REFERENCES warehouse_bin (bin_id);

CREATE INDEX IF NOT EXISTS idx_wh_outbound_line_bin
    ON warehouse_outbound_line (bin_id)
    WHERE bin_id IS NOT NULL;
