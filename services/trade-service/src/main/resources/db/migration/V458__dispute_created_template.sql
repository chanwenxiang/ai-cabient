-- MIGRATION_KIND: backfill
-- COMPETITOR_REF: N/A
-- R18-3（四端审计 2026-11-12）：DisputeService.notifyConsumerLateRecognition 引用
-- "dispute_created" 模板，但全仓迁移从未种库 ⇒ NotificationService 模板未命中只
-- log.debug 后 return，迟到识别兜账 v2 承诺的「消费者站内信触达」静默失效。
-- 对账实证（2026-11-12）：代码引用 dispute_created / dispute_resolved / merchant_fee_reminder
-- 三个码，仅本条缺失。种子后见 scripts/check-notification-template-seeded.mjs 门禁防复发。

INSERT INTO notification_template (template_code, template_name, channel, title_template, body_template, audience, category, status)
VALUES ('dispute_created', '争议单已创建', 'IN_APP',
        '争议单已创建',
        '您的订单已创建争议单 {disputeNo}：{reason}。我们核实后会通知您处理结果。',
        'CONSUMER', 'DISPUTE', 'ACTIVE')
ON CONFLICT (template_code) DO NOTHING;

UPDATE notification_template SET category = 'DISPUTE' WHERE template_code = 'dispute_created' AND (category IS NULL OR category = '');
