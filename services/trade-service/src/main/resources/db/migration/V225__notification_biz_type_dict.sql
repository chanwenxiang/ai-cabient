-- V225: notification_log.biz_type labels for admin message records.

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
SELECT 'notification_biz_type', '站内信业务类型', 'ACTIVE', 0, 'notification_log.biz_type'
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'notification_biz_type');

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status, remark)
SELECT v.dict_type, v.dict_value, v.dict_label, v.sort_order, 'ACTIVE', v.remark
FROM (VALUES
    ('notification_biz_type', 'ORDER',         '订单',       1, '订单支付/状态'),
    ('notification_biz_type', 'RECHARGE',      '充值',       2, '余额充值'),
    ('notification_biz_type', 'REPLENISHMENT', '补货',       3, '补货任务'),
    ('notification_biz_type', 'DISPUTE',       '争议/售后',  4, '申诉处理'),
    ('notification_biz_type', 'COUPON',        '优惠券',     5, '券到期提醒'),
    ('notification_biz_type', 'POINTS',        '积分',       6, '积分到期'),
    ('notification_biz_type', 'RECALL',        '用户召回',   7, '活动召回'),
    ('notification_biz_type', 'MERCHANT',      '商户通知',   8, '商户结算等'),
    ('notification_biz_type', 'SETTLEMENT',    '结算',       9, '分账结算'),
    ('notification_biz_type', 'SESSION',       '购物会话',  10, '开门/购物'),
    ('notification_biz_type', 'OPS_MANUAL',    '运营手工',  11, '后台手工发送')
) AS v(dict_type, dict_value, dict_label, sort_order, remark)
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data d
    WHERE d.dict_type = v.dict_type AND d.dict_value = v.dict_value
);

COMMENT ON COLUMN notification_log.biz_type IS 'ORDER|RECHARGE|REPLENISHMENT|OPS_MANUAL 等；见 notification_biz_type 字典';
