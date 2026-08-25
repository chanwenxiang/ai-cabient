-- V103: 订单行记录货道（用于货道级热区/坪效分析）
ALTER TABLE cabinet_order_line ADD COLUMN IF NOT EXISTS slot_id VARCHAR(32);
CREATE INDEX IF NOT EXISTS idx_cabinet_order_line_slot ON cabinet_order_line (slot_id);
