-- V224: verify_channel dict for phone_verify_log display (SMS vs pay_channel).

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
SELECT 'verify_channel', '手机验证渠道', 'ACTIVE', 0, 'phone_verify_log.channel'
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'verify_channel');

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status, remark)
SELECT v.dict_type, v.dict_value, v.dict_label, v.sort_order, 'ACTIVE', v.remark
FROM (VALUES
    ('verify_channel', 'SMS',        '短信验证码',   1, '登录/注册短信验证'),
    ('verify_channel', 'SMS_RESET',  '短信重置密码', 2, '找回密码短信验证'),
    ('verify_channel', 'WECHAT',     '微信',         3, '微信绑定验证'),
    ('verify_channel', 'ALIPAY',     '支付宝',       4, '支付宝绑定验证')
) AS v(dict_type, dict_value, dict_label, sort_order, remark)
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data d
    WHERE d.dict_type = v.dict_type AND d.dict_value = v.dict_value
);

COMMENT ON COLUMN phone_verify_log.channel IS 'SMS|SMS_RESET|WECHAT|ALIPAY；非 pay_channel';
COMMENT ON COLUMN phone_verify_log.merchant_id IS '可选；消费者短信验证通常为空，商户端/手工登记可填';
