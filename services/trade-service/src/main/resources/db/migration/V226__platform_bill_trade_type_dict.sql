-- V226: payment_platform_bill_line.trade_type labels for admin reconciliation detail.

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
SELECT 'platform_bill_trade_type', '对账账单交易类型', 'ACTIVE', 0, 'payment_platform_bill_line.trade_type'
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'platform_bill_trade_type');

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status, remark)
SELECT v.dict_type, v.dict_value, v.dict_label, v.sort_order, 'ACTIVE', v.remark
FROM (VALUES
    ('platform_bill_trade_type', 'PAY',      '支付',       1, '柜机订单收款'),
    ('platform_bill_trade_type', 'RECHARGE', '充值',       2, '余额充值'),
    ('platform_bill_trade_type', 'REFUND',   '退款',       3, '退款流水'),
    ('platform_bill_trade_type', 'WECHAT',   '微信支付',   4, '微信账单行兼容码'),
    ('platform_bill_trade_type', 'ALIPAY',   '支付宝支付', 5, '支付宝账单行兼容码')
) AS v(dict_type, dict_value, dict_label, sort_order, remark)
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data d
    WHERE d.dict_type = v.dict_type AND d.dict_value = v.dict_value
);

COMMENT ON COLUMN payment_platform_bill_line.trade_type IS 'PAY|RECHARGE|REFUND 等；见 platform_bill_trade_type 字典';
