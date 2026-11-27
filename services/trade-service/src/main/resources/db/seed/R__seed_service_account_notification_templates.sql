-- COMPETITOR_REF: CB-088
-- 服务号资金/售后通知模板种子：站内信保留，外部渠道由 NotificationService 按 userId 分发。

INSERT INTO notification_template
    (template_code, template_name, channel, channels, category, title_template, body_template, audience, status)
VALUES
    ('refund_success', '退款成功通知', 'IN_APP', 'IN_APP,WECHAT_SUBSCRIBE', 'ORDER',
     '退款成功', '您的订单 {orderId} 已退款 {amount} 元。', 'CONSUMER', 'ACTIVE'),
    ('merchant_withdraw_success', '提现成功通知', 'IN_APP', 'IN_APP,WECHAT_SUBSCRIBE', 'MERCHANT',
     '提现成功通知', '提现 {amount} 元已到账，实际到账 {receivedAmount} 元，手续费 {fee} 元。', 'MERCHANT', 'ACTIVE'),
    ('merchant_withdraw_failed', '提现失败提醒', 'IN_APP', 'IN_APP,WECHAT_SUBSCRIBE', 'MERCHANT',
     '提现失败提醒', '提现 {amount} 元失败：{failureReason}。', 'MERCHANT', 'ACTIVE')
ON CONFLICT (template_code) DO UPDATE
SET category = EXCLUDED.category,
    template_name = EXCLUDED.template_name,
    channels = EXCLUDED.channels,
    title_template = EXCLUDED.title_template,
    body_template = EXCLUDED.body_template,
    audience = EXCLUDED.audience,
    status = EXCLUDED.status;

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status, remark)
SELECT v.dict_type, v.dict_value, v.dict_label, v.sort_order, 'ACTIVE', v.remark
FROM (VALUES
    ('notification_biz_type', 'REFUND', '退款', 14, '订单/余额退款'),
    ('notification_biz_type', 'BALANCE_REFUND', '余额退款', 15, '余额退款审批'),
    ('notification_biz_type', 'WITHDRAW_SUCCESS', '提现成功', 16, '商户提现到账'),
    ('notification_biz_type', 'WITHDRAW_FAILED', '提现失败', 17, '商户提现失败')
) AS v(dict_type, dict_value, dict_label, sort_order, remark)
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data d
    WHERE d.dict_type = v.dict_type AND d.dict_value = v.dict_value
);
