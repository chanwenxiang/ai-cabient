-- MIGRATION_KIND: schema
-- COMPETITOR_REF: CB-051
-- NOTES: 设备详情商品卡片补「上次配送后库存」字段（CB-051 收尾）：device_sku_inventory 新增
--       last_restock_qty，补货完成（completeTask 应用行后）按柜×商品写快照；
--       NULL=该商品自字段上线以来未发生过补货，前端展示 '--'。可空整数，无回填（历史不可考）。
-- TABLE: device_sku_inventory（新表，行数=柜×商品组合，量级小）
ALTER TABLE device_sku_inventory ADD COLUMN IF NOT EXISTS last_restock_qty INTEGER;
COMMENT ON COLUMN device_sku_inventory.last_restock_qty IS '上次配送后库存快照（补货完成后写入；NULL=无补货记录，CB-051）';
