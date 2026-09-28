-- L2 跟进：定时任务「周期费用月结出账」调度说明中文化
-- MIGRATION_KIND: backfill
-- V256 种子行把配置键名写进了 schedule_desc（『由 aicabinet.fee-bill.auto-generate-cron 配置』），
-- 运营台「调度说明」列与其他任务的中文口径不一致。对齐为注册表同款中文描述；
-- 配置键引用保留在 remark，不影响运维调整入口。

UPDATE scheduled_task
SET schedule_desc = '每月 1 日 01:30（可经 aicabinet.fee-bill.auto-generate-cron 调整）'
WHERE task_key = 'ops-fee-bill-monthly'
  AND schedule_desc = '由 aicabinet.fee-bill.auto-generate-cron 配置';
