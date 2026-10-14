-- MIGRATION_KIND: backfill
-- COMPETITOR_REF: CB-004
-- 费用催缴通知模板（CB-004 追缴升级路径：先催 → 限制；欠费锁柜前置动作，2026-10-14 拍板）。
INSERT INTO notification_template (template_code, template_name, channel, title_template, body_template, audience, status)
VALUES ('merchant_fee_reminder', '费用催缴通知', 'IN_APP',
        '费用催缴通知',
        '商户名称：{merchantName}
提醒内容：{reminder}
欠费金额：{debt} 元
请及时充值，累计欠费将按约定触发柜机锁定。', 'MERCHANT', 'ACTIVE')
ON CONFLICT (template_code) DO NOTHING;
