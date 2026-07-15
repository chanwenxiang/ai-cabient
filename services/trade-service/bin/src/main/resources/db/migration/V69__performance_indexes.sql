-- P1: 高频查询场景缺少的索引

-- 订单按用户查询（我的订单列表）
CREATE INDEX IF NOT EXISTS idx_cabinet_order_user_created
    ON cabinet_order (user_id, created_at DESC);

-- 订单按设备+状态查询（运营后台设备视角）
CREATE INDEX IF NOT EXISTS idx_cabinet_order_device_status
    ON cabinet_order (device_id, status, created_at DESC);

-- 订单行商品查询
CREATE INDEX IF NOT EXISTS idx_cabinet_order_line_sku
    ON cabinet_order_line (sku_id);

-- 设备批次按过期时间查询（FEFO 扣减+预警）
CREATE INDEX IF NOT EXISTS idx_device_sku_lot_device_expiry
    ON device_sku_lot (device_id, expiry_date)
    WHERE status = 'ON_SALE';

-- 会话按设备+状态查询
CREATE INDEX IF NOT EXISTS idx_shopping_session_device_state
    ON shopping_session (device_id, state);

-- 会话开门/关门时间范围查询
CREATE INDEX IF NOT EXISTS idx_shopping_session_open_time
    ON shopping_session (open_time)
    WHERE open_time IS NOT NULL;

-- 支付操作按幂等键查询
CREATE INDEX IF NOT EXISTS idx_payment_operation_idempotency
    ON payment_operation (idempotency_key);

-- 充值按状态查询
CREATE INDEX IF NOT EXISTS idx_recharge_order_status_created
    ON recharge_order (status, created_at DESC);

-- 补货任务按状态查询
CREATE INDEX IF NOT EXISTS idx_replenishment_task_status_device
    ON replenishment_task (status, device_id, created_at DESC);

-- 分账按状态查询
CREATE INDEX IF NOT EXISTS idx_revenue_split_status_device
    ON order_revenue_split (status, device_id, created_at DESC);

-- OTA 按状态查询
CREATE INDEX IF NOT EXISTS idx_ota_release_status
    ON ota_release (status, created_at DESC);

-- 争议按用户查询
CREATE INDEX IF NOT EXISTS idx_dispute_ticket_status_created
    ON dispute_ticket (status, created_at DESC);

-- 温控读取历史索引见 V38（device_id, reported_at DESC）

-- 仓库出库按路线+状态
CREATE INDEX IF NOT EXISTS idx_warehouse_outbound_status
    ON warehouse_outbound (status, created_at DESC);

-- VACUUM 和分析（建议定时执行）
-- ANALYZE;
