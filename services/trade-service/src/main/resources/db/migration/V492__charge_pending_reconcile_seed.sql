-- MIGRATION_REVIEWED: yes（新定时任务登记行；代码侧 ScheduledTaskRegistry 已注册、@Scheduled 已接 tryBegin）
-- MIGRATION_KIND: backfill（scheduled_task 单行 INSERT）
-- LOCK_RISK: low（单行 INSERT）
-- TABLES: scheduled_task (+1)
-- NOTES: B1-B2 CHARGE_PENDING 对账任务（Spring 常驻，不进 XXL；每 2 分钟，2 分钟静默后查单）
INSERT INTO scheduled_task (task_key, task_name, task_group, schedule_desc, remark)
VALUES ('charge-pending-reconcile', '扣款回执对账', 'FINANCE', '每 2 分钟',
        'CHARGE_PENDING 2 分钟静默后按尝试渠道查单：SUCCESS 补收口 / FAILED 终结 / UNKNOWN 下一轮；15 分钟首告、60 分钟升级')
ON CONFLICT (task_key) DO NOTHING;
