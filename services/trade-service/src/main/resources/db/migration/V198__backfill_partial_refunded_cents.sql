-- 部分/全额退款：用支付流水回填漏写的 refunded_cents（V195 仅兜底了全额 REFUNDED）
UPDATE cabinet_order o
SET refunded_cents = sub.refund_sum
FROM (
    SELECT order_id, SUM(amount_cents)::INT AS refund_sum
    FROM payment_operation
    WHERE operation_type = 'REFUND'
      AND status = 'COMPLETED'
      AND order_id IS NOT NULL
    GROUP BY order_id
) sub
WHERE o.order_id = sub.order_id
  AND o.status IN ('PARTIAL_REFUNDED', 'REFUNDED')
  AND COALESCE(o.refunded_cents, 0) = 0
  AND sub.refund_sum > 0;
