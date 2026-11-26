-- COMPETITOR_REF: CB-088
-- 服务号资金/售后通知模板种子：站内信保留，外部渠道由 NotificationService 按 userId 分发。

INSERT INTO notification_template
    (template_code, template_name, channel, channels, category, title_template, body_template, audience, status)
VALUES
    ('refund_success', '退款成功通知', 'IN_APP', 'IN_APP,WECHAT_SUBSCRIBE', '交易通知',
     '退款成功', '您的订单 {orderId} 已退款 {amount} 元。', 'CONSUMER', 'ACTIVE'),
    ('merchant_withdraw_success', '提现成功通知', 'IN_APP', 'IN_APP,WECHAT_SUBSCRIBE', '资金通知',
     '提现成功通知', '提现 {amount} 元已到账，实际到账 {receivedAmount} 元，手续费 {fee} 元。', 'MERCHANT', 'ACTIVE'),
    ('merchant_withdraw_failed', '提现失败提醒', 'IN_APP', 'IN_APP,WECHAT_SUBSCRIBE', '资金通知',
     '提现失败提醒', '提现 {amount} 元失败：{failureReason}。', 'MERCHANT', 'ACTIVE')
ON CONFLICT (template_code) DO NOTHING;
