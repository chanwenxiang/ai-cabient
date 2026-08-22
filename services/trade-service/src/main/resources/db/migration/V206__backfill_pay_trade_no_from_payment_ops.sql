-- Legacy 购物单：从 payment_operation 回填 pay_trade_no（仅空值，渠道 WECHAT/ALIPAY）。
UPDATE cabinet_order co
SET pay_trade_no = src.gateway_trade_no
FROM (
    SELECT DISTINCT ON (po.order_id)
           po.order_id,
           po.gateway_trade_no
    FROM payment_operation po
    WHERE po.status = 'COMPLETED'
      AND po.operation_type IN ('CHARGE', 'ADJUST_CHARGE')
      AND po.gateway_trade_no IS NOT NULL
      AND TRIM(po.gateway_trade_no) <> ''
      AND po.order_id IS NOT NULL
    ORDER BY po.order_id, po.created_at DESC
) src
WHERE co.order_id = src.order_id
  AND (co.pay_trade_no IS NULL OR TRIM(co.pay_trade_no) = '')
  AND UPPER(COALESCE(co.pay_channel, '')) IN ('WECHAT', 'ALIPAY');

-- Legacy 充值单：从 recharge-credit 流水回填 wx_transaction_id / alipay_trade_no。
UPDATE recharge_order ro
SET wx_transaction_id = po.gateway_trade_no
FROM payment_operation po
WHERE po.idempotency_key = 'recharge-credit:' || ro.order_id
  AND po.gateway_trade_no IS NOT NULL
  AND TRIM(po.gateway_trade_no) <> ''
  AND (ro.wx_transaction_id IS NULL OR TRIM(ro.wx_transaction_id) = '')
  AND UPPER(COALESCE(ro.channel, '')) = 'WECHAT';

UPDATE recharge_order ro
SET alipay_trade_no = po.gateway_trade_no
FROM payment_operation po
WHERE po.idempotency_key = 'recharge-credit:' || ro.order_id
  AND po.gateway_trade_no IS NOT NULL
  AND TRIM(po.gateway_trade_no) <> ''
  AND (ro.alipay_trade_no IS NULL OR TRIM(ro.alipay_trade_no) = '')
  AND UPPER(COALESCE(ro.channel, '')) = 'ALIPAY';
