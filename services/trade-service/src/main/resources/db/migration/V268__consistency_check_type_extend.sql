-- 扩展一致性巡检类型字典（孤儿批 / 积分恒等 / 商户线路钱包 / 分账 / 货道 / 仓存负库存 / 超发券）

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status, remark)
SELECT v.dict_type, v.dict_value, v.dict_label, v.sort_order, 'ACTIVE', 'consistency-extend'
FROM (VALUES
    ('consistency_check_type', 'WALLET_BALANCE', '用户钱包', 60),
    ('consistency_check_type', 'REFUND_AMOUNT', '退款金额', 70),
    ('consistency_check_type', 'ORDER_LINE_SUM', '订单行金额', 80),
    ('consistency_check_type', 'COUPON_USED_LINK', '券核销关联', 90),
    ('consistency_check_type', 'INVENTORY_ORPHAN_LOT', '孤儿批次', 100),
    ('consistency_check_type', 'POINTS_IDENTITY', '积分恒等式', 110),
    ('consistency_check_type', 'MERCHANT_WALLET', '商户钱包', 120),
    ('consistency_check_type', 'LINE_WALLET', '线路钱包', 130),
    ('consistency_check_type', 'REVENUE_SPLIT_SUM', '分账金额', 140),
    ('consistency_check_type', 'REVENUE_SPLIT_MISSING', '分账缺失', 150),
    ('consistency_check_type', 'SLOT_SKU_MISMATCH', '货道SKU', 160),
    ('consistency_check_type', 'SLOT_CAPACITY', '货道容量', 170),
    ('consistency_check_type', 'SLOT_PHYSICAL', '货道盘点', 180),
    ('consistency_check_type', 'WAREHOUSE_NEGATIVE', '仓存负库存', 190),
    ('consistency_check_type', 'COUPON_OVER_QUOTA', '发券超配额', 200)
) AS v(dict_type, dict_value, dict_label, sort_order)
WHERE EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'consistency_check_type')
  AND NOT EXISTS (
    SELECT 1 FROM sys_dict_data d
    WHERE d.dict_type = v.dict_type AND d.dict_value = v.dict_value
  );
