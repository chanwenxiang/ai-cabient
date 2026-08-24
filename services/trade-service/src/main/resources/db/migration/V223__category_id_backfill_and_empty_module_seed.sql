-- V223: Align sku_catalog.category_id with category text; mark deprecated tables; seed empty demo modules.

-- ---------------------------------------------------------------------------
-- Part 1: category_id backfill from aliyun_category_mapping.category_name
-- ---------------------------------------------------------------------------

UPDATE sku_catalog s
SET category_id = m.category_id
FROM aliyun_category_mapping m
WHERE s.category IS NOT NULL
  AND TRIM(s.category) <> ''
  AND m.category_name = TRIM(s.category)
  AND (s.category_id IS NULL OR s.category_id <> m.category_id);

COMMENT ON COLUMN sku_catalog.category_id IS '阿里云视觉类目 ID；与 category 文本联动，写入 SKU 时自动匹配';

COMMENT ON TABLE promotion_device IS '已废弃：活动设备范围请用 promotion_activity.device_scope=SPECIFIC + rule_config.deviceIds';

-- ---------------------------------------------------------------------------
-- Part 2: Idempotent demo seed (modules still empty after V222)
-- ---------------------------------------------------------------------------

INSERT INTO phone_verify_log (user_id, phone, channel, merchant_id, verified_at)
SELECT 10001, '13900000002', 'WECHAT', 'MCH-DEFAULT', NOW() - INTERVAL '3 day'
WHERE NOT EXISTS (SELECT 1 FROM phone_verify_log LIMIT 1);

INSERT INTO phone_verify_log (user_id, phone, channel, merchant_id, verified_at)
SELECT 10001, '13900000003', 'SMS', 'MCH-DEFAULT', NOW() - INTERVAL '1 day'
WHERE EXISTS (SELECT 1 FROM phone_verify_log LIMIT 1)
  AND NOT EXISTS (SELECT 1 FROM phone_verify_log WHERE phone = '13900000003');

INSERT INTO device_ops_event (device_id, event_type, severity, title, detail, created_at)
SELECT 'CAB-001', 'OFFLINE', 'WARN', '设备离线', '演示：心跳超时 15 分钟', NOW() - INTERVAL '2 hour'
WHERE NOT EXISTS (SELECT 1 FROM device_ops_event LIMIT 1);

INSERT INTO device_ops_event (device_id, event_type, severity, title, detail, created_at)
SELECT 'CAB-001', 'NO_SALES', 'INFO', '长时间无销售', '演示：24h 无成交', NOW() - INTERVAL '6 hour'
WHERE EXISTS (SELECT 1 FROM device_info WHERE device_id = 'CAB-001')
  AND NOT EXISTS (SELECT 1 FROM device_ops_event WHERE event_type = 'NO_SALES' AND device_id = 'CAB-001');

INSERT INTO warehouse_stocktake (
    stocktake_no, warehouse_id, mode, status, book_qty, notes, operator_id, created_at
)
SELECT 'ST-DEMO-001', 'WH-DEMO-001', 'OPEN', 'DRAFT', 360, '演示：整仓盘点草稿', 100000001, NOW()
WHERE EXISTS (SELECT 1 FROM warehouse WHERE warehouse_id = 'WH-DEMO-001')
  AND NOT EXISTS (SELECT 1 FROM warehouse_stocktake WHERE stocktake_no = 'ST-DEMO-001');

INSERT INTO warehouse_stocktake_line (stocktake_id, sku_id, batch_no, book_qty, counted_qty, diff_qty)
SELECT st.stocktake_id, 'SKU-MILK-001', 'B-WH-MILK-01', 30, NULL, 0
FROM warehouse_stocktake st
WHERE st.stocktake_no = 'ST-DEMO-001'
  AND NOT EXISTS (
      SELECT 1 FROM warehouse_stocktake_line l
      WHERE l.stocktake_id = st.stocktake_id AND l.sku_id = 'SKU-MILK-001'
  );
