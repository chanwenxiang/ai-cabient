-- 余额流水既服务购物订单扣款，也服务充值/运营调账；后两者无 cabinet_order 可挂接。
-- 允许 order_id 为空，同时保留对 cabinet_order 的外键（NULL 不触发 FK）。
ALTER TABLE payment_operation
    ALTER COLUMN order_id DROP NOT NULL;
