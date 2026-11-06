-- MIGRATION_KIND: backfill
-- 短信到达率巡检调度登记（C 档修订形态：按 (号码,发送日) 拉阿里云 QuerySendDetails 做聚合告警）。
-- ScheduledTaskRegistry.register("sms-delivery-probe") 需要登记行，否则运营台不可见、
-- 无法启停/手动触发，且执行记录被 ScheduledTaskService.finish() 静默丢弃（V364/V409 同因）。
-- 口径：每小时一轮，窗口 sms.delivery_probe.lookback_days（代码夹 1..6，受阿里云保留期约束）；
-- 失败占比 > sms.delivery_probe.fail_alert_percent 才告警，低于 min_sample 不判定。
-- 开关默认 OFF：当前无真实发送量，自动外呼没有意义（fail-closed）。
INSERT INTO scheduled_task (task_key, task_name, task_group, schedule_desc, remark) VALUES
('sms-delivery-probe', '短信到达率巡检', 'OPS', '每 60 分钟',
 '按 (手机号, 发送日) 调阿里云 QuerySendDetails 聚合送达/发送中/失败；回执无 BizId 故只做聚合口径、不逐行回填。告警类型 SMS_DELIVERY_FAILURE_HIGH；总闸 sms.delivery_probe.enabled 默认关')
ON CONFLICT (task_key) DO NOTHING;
