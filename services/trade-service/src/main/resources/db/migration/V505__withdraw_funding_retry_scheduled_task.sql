-- COMPETITOR_REF: CB-033
-- MIGRATION_KIND: backfill
-- MIGRATION_REVIEWED: yes
-- TABLES: scheduled_task（单行 INSERT … ON CONFLICT DO NOTHING）
-- LOCK_RISK: low
-- ROLLBACK: 不回滚 —— 删行会让运营台看不见该任务；真要停用请把 enabled 置 false
-- NOTES: 每小时扫描备付不足/ NOT_ENOUGH 的商户提现单，按渠道结果自动重试。

INSERT INTO scheduled_task (task_key, task_name, task_group, schedule_desc, remark) VALUES
('withdraw-funding-retry', '提现备付恢复重试', 'FINANCE', '每小时 20 分',
 '每小时扫描备付不足或微信 NOT_ENOUGH 的 APPROVED/FAILED 提现单；自动重试跳过本地资金池余额硬门，由渠道结果裁决，复用原幂等键；成功落 PAID，NOT_ENOUGH 排下一小时，其它失败停止自动重试（XXL-JOB 托管）')
ON CONFLICT (task_key) DO NOTHING;
