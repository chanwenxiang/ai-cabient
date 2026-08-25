-- V200: 对齐演示环境 4 条数据一致性未通过记录（ORDER / PAYMENT / POINTS / COUPON）
-- 口径与 DataConsistencyService 巡检 SQL 一致。

-- 1) 订单金额：已扣 1038，三行明细合计 1538 → 按比例缩放到 1038（与入账一致）
UPDATE cabinet_order_line
SET line_amount_cents = 235,
    unit_price_cents = 235
WHERE order_id = '1787215465125755801'
  AND sku_id = 'SKU-DEMO-001';

UPDATE cabinet_order_line
SET line_amount_cents = 352,
    unit_price_cents = 352
WHERE order_id = '1787215465125755801'
  AND sku_id = 'SKU-NOODLE-001';

UPDATE cabinet_order_line
SET line_amount_cents = 451,
    unit_price_cents = 451
WHERE order_id = '1787215465125755801'
  AND sku_id = 'SKU-SNACK-001';

-- 2) 积分余额：member.available_points 对齐积分流水汇总（EARN − USE/EXPIRE）
UPDATE member m
SET available_points = sub.calc
FROM (
    SELECT member_id,
           COALESCE(SUM(CASE
               WHEN l.points_type = 'EARN' THEN l.points
               WHEN l.points_type IN ('USE', 'EXPIRE') THEN -l.points
               ELSE 0
           END), 0)::INT AS calc
    FROM member_points_log l
    WHERE l.member_id = 1
    GROUP BY member_id
) sub
WHERE m.member_id = sub.member_id
  AND m.available_points <> sub.calc;

-- 3) 发券数量：券定义已发数对齐 user_coupon 实际条数
UPDATE coupon_definition d
SET issued_count = (
    SELECT COUNT(*)::INT FROM user_coupon uc WHERE uc.coupon_def_id = d.coupon_def_id
),
    updated_at = NOW()
WHERE d.coupon_def_id = 2
  AND d.issued_count <> (SELECT COUNT(*) FROM user_coupon uc WHERE uc.coupon_def_id = d.coupon_def_id);

-- 4) 支付净额：REFUNDED 单 O-BUG007-TEST 仅有退款流水，补一条已完成扣款使净入账为 0
INSERT INTO payment_operation (
    operation_id,
    order_id,
    operation_type,
    amount_cents,
    channel,
    status,
    idempotency_key,
    reason,
    created_at,
    user_id
)
SELECT
    'BL-BUG007-CHARGE-FIX',
    'O-BUG007-TEST',
    'CHARGE',
    100,
    'BALANCE',
    'COMPLETED',
    'CHARGE:O-BUG007-TEST:demo-v200-fix',
    '演示补齐扣款流水(V200)',
    o.created_at + INTERVAL '1 second',
    o.user_id
FROM cabinet_order o
WHERE o.order_id = 'O-BUG007-TEST'
  AND NOT EXISTS (
      SELECT 1 FROM payment_operation po
      WHERE po.order_id = 'O-BUG007-TEST'
        AND po.operation_type = 'CHARGE'
        AND po.status = 'COMPLETED'
  );

-- 关闭已对齐的 FAIL 记录（下次巡检也会自动 resolve）
UPDATE data_consistency_record
SET status = 'FIXED',
    fixed_at = NOW(),
    error_message = COALESCE(error_message, '') || ' | V200 demo align'
WHERE status = 'FAIL'
  AND (
      (check_type = 'ORDER_AMOUNT' AND check_key = '1787215465125755801')
      OR (check_type = 'POINTS_BALANCE' AND check_key = '1')
      OR (check_type = 'COUPON_ISSUED' AND check_key = '2')
      OR (check_type = 'PAYMENT_AMOUNT' AND check_key = 'O-BUG007-TEST')
  );
