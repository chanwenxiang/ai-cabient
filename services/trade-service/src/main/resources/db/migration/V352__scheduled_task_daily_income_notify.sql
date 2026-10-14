-- MIGRATION_KIND: backfill
-- 商户日结通知调度登记（MerchantDailyIncomeNotifyScheduler，CB-020 补充）。
-- tryBegin("merchant-daily-income-notify") 需登记行，否则运营台不可见/不可启停（V334 同因）。
INSERT INTO scheduled_task (task_key, task_name, task_group, schedule_desc, remark) VALUES
('merchant-daily-income-notify', '商户日结通知（T+1 收入结算）', 'TRADE', '每日 00:20',
 '每日 00:20 汇总昨日各商户入账（分账 merchant_cents 按创建日聚合，冲正不计），只发昨日有收入（>0）的商户；站内信三字段对齐老系统「收入结算通知」（商户名称/结算周期/收入金额）；biz_id=日期 幂等查重，重跑不重发')
ON CONFLICT (task_key) DO NOTHING;
