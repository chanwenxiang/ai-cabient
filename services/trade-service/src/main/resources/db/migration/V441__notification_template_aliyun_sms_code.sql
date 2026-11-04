-- 2026-11-04：通知短信从「一条全局催缴模板」改成「模板 ↔ 阿里云 CODE 一一对应」。
--
-- 背景：sendViaAliyunTemplate 原先把 system_config.notify.sms.dunning_template 当唯一模板码，
--      于是任何带 smsParams 且 channels 含 SMS 的通知（如 order_paid{orderId,amount}）都会被套成
--      催缴文案——已支付用户会收到「您的订单…有 X 元未支付」。总闸历史上恒为 false，缺陷一直没暴露。
-- 改法：CODE 存到模板行自身；未登记 = 该模板不发短信（不猜、不复用别的模板）。
--      同时删掉全局键，避免「两条真值来源」再漂移。
-- 阿里云官方口径（2026-11 核对）：已过审模板不可修改，只能克隆后重提审换新 CODE；
--      TemplateParam 参数个数须与模板变量个数一致；正文含联系方式/链接会被运营商按引流信息拦截。
-- MIGRATION_KIND: backfill
-- COMPETITOR_REF: N/A(仅加列 + 搬移配置，无新建业务表)
ALTER TABLE notification_template ADD COLUMN IF NOT EXISTS aliyun_template_code VARCHAR(64);

COMMENT ON COLUMN notification_template.aliyun_template_code IS
    '该模板对应的阿里云短信模板 CODE（通知类，变量须与本模板一致）；NULL=不通过阿里云发短信';

UPDATE notification_template
   SET aliyun_template_code = 'SMS_512512665'
 WHERE template_code = 'unpaid_order_dunning'
   AND aliyun_template_code IS NULL;

DELETE FROM system_config WHERE config_key = 'notify.sms.dunning_template';
