-- V202: 生产口径修复 — 券已发数对齐、恢复 V200 误缩放的订单明细原价

-- 券定义 issued_count 与 user_coupon 实际条数对齐（防 seed/并发漂移）
UPDATE coupon_definition d
SET issued_count = sub.cnt,
    updated_at = NOW()
FROM (
    SELECT coupon_def_id, COUNT(*)::INT AS cnt
    FROM user_coupon
    GROUP BY coupon_def_id
) sub
WHERE d.coupon_def_id = sub.coupon_def_id
  AND d.issued_count <> sub.cnt;

UPDATE coupon_definition d
SET issued_count = 0,
    updated_at = NOW()
WHERE NOT EXISTS (SELECT 1 FROM user_coupon uc WHERE uc.coupon_def_id = d.coupon_def_id)
  AND d.issued_count <> 0;

-- V200 误将明细缩放到应付额；业务口径应为「明细原价 − 券/会员折扣 = 订单头」
UPDATE cabinet_order_line
SET line_amount_cents = 350,
    unit_price_cents = 350
WHERE order_id = '1787215465125755801'
  AND sku_id = 'SKU-DEMO-001'
  AND line_amount_cents = 235;

UPDATE cabinet_order_line
SET line_amount_cents = 520,
    unit_price_cents = 520
WHERE order_id = '1787215465125755801'
  AND sku_id = 'SKU-NOODLE-001'
  AND line_amount_cents = 352;

UPDATE cabinet_order_line
SET line_amount_cents = 668,
    unit_price_cents = 668
WHERE order_id = '1787215465125755801'
  AND sku_id = 'SKU-SNACK-001'
  AND line_amount_cents = 451;

-- 无扣款却仅有退款的 REFUNDED 单：去掉孤儿 REFUND，净入账恢复为 0
DELETE FROM payment_operation po
WHERE po.order_id = 'O-BUG007-TEST'
  AND po.operation_type = 'REFUND'
  AND po.status = 'COMPLETED'
  AND NOT EXISTS (
      SELECT 1 FROM payment_operation ch
      WHERE ch.order_id = po.order_id
        AND ch.status = 'COMPLETED'
        AND ch.operation_type IN ('CHARGE', 'ADJUST_CHARGE')
  );

UPDATE data_consistency_record
SET status = 'FIXED',
    fixed_at = NOW(),
    error_message = COALESCE(error_message, '') || ' | V202 prod align'
WHERE status = 'FAIL';
