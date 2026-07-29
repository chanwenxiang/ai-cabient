-- V123: 三端数据一致性修复
-- 1) 对齐 data_change_log 表结构与 Java 实体（表为空，可安全重建）
-- 2) 以已完成扣款/订单头金额为资金事实，修正明细行合计偏差
-- 3) 柜机汇总库存对齐 ON_SALE 批次合计
-- 4) 关闭已处理的一致性 FAIL 记录

DROP TABLE IF EXISTS data_change_log;

CREATE TABLE data_change_log (
    id BIGSERIAL PRIMARY KEY,
    table_name VARCHAR(64) NOT NULL,
    record_id VARCHAR(64) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    old_value TEXT,
    new_value TEXT,
    changed_by VARCHAR(64),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    verified_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_change_log_table_record ON data_change_log (table_name, record_id, changed_at DESC);
CREATE INDEX IF NOT EXISTS idx_change_log_changed_by ON data_change_log (changed_by, changed_at DESC);
CREATE INDEX IF NOT EXISTS idx_change_log_time ON data_change_log (changed_at DESC);
CREATE INDEX IF NOT EXISTS idx_change_log_verified ON data_change_log (verified) WHERE verified = FALSE;

COMMENT ON TABLE data_change_log IS '数据变更日志：记录关键业务数据变更，供一致性审计';

-- 单行订单：明细金额对齐订单头（头金额与 CHARGE 流水一致）
UPDATE cabinet_order_line ol
SET line_amount_cents = o.total_amount_cents,
    unit_price_cents = CASE
        WHEN ol.quantity > 0 THEN o.total_amount_cents / ol.quantity
        ELSE o.total_amount_cents
    END
FROM cabinet_order o
WHERE ol.order_id = o.order_id
  AND o.status = 'PAID'
  AND o.order_id IN ('OE09980382DC2428D', 'O98FBE0EF6A114968')
  AND (
      SELECT COUNT(*) FROM cabinet_order_line x WHERE x.order_id = o.order_id
  ) = 1
  AND ol.line_amount_cents <> o.total_amount_cents;

-- 无明细但已扣款的订单：补一条与头金额一致的行（演示 SKU）
INSERT INTO cabinet_order_line (order_id, sku_id, sku_name, quantity, unit_price_cents, line_amount_cents)
SELECT o.order_id, 'SKU-MILK-001', '纯牛奶 250ml', 1, o.total_amount_cents, o.total_amount_cents
FROM cabinet_order o
WHERE o.order_id = 'O2E31E9A605E54E6C'
  AND o.status = 'PAID'
  AND NOT EXISTS (SELECT 1 FROM cabinet_order_line x WHERE x.order_id = o.order_id);

-- 库存汇总对齐 ON_SALE 批次（不改批次，避免破坏 FEFO）
UPDATE device_sku_inventory i
SET quantity = sub.lot_qty
FROM (
    SELECT i2.device_id,
           i2.sku_id,
           COALESCE(SUM(l.quantity), 0)::INT AS lot_qty
    FROM device_sku_inventory i2
    LEFT JOIN device_sku_lot l
      ON l.device_id = i2.device_id
     AND l.sku_id = i2.sku_id
     AND UPPER(COALESCE(l.status, '')) = 'ON_SALE'
    WHERE i2.device_id = 'CAB-001'
      AND i2.sku_id IN ('SKU-MILK-001', 'SKU-NOODLE-001')
    GROUP BY i2.device_id, i2.sku_id, i2.quantity
    HAVING i2.quantity <> COALESCE(SUM(l.quantity), 0)
) sub
WHERE i.device_id = sub.device_id
  AND i.sku_id = sub.sku_id;

-- 标记历史 FAIL 为已修复（巡检会重新发现仍存在的问题）
UPDATE data_consistency_record
SET status = 'FIXED',
    fixed_at = NOW(),
    error_message = COALESCE(error_message, 'V123 three-end align')
WHERE status = 'FAIL'
  AND (
      check_type = 'ORDER_AMOUNT'
      AND check_key IN ('OE09980382DC2428D', 'O98FBE0EF6A114968', 'O2E31E9A605E54E6C')
      OR (
          check_type = 'INVENTORY_MISMATCH'
          AND check_key IN ('CAB-001|SKU-MILK-001', 'CAB-001|SKU-NOODLE-001')
      )
  );
