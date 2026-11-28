-- MIGRATION_KIND: backfill
-- COMPETITOR_REF: CB-020
-- MIGRATION_REVIEWED: yes（退款查单与自动重发拆频，查单只读、重发仍默认停用）
-- LOCK_RISK: low（scheduled_task 单行更新/insert）
-- TABLES: scheduled_task
-- NOTES: refund-query 每分钟只读查单并推进本地终态；refund-retry 每小时执行重发与耗尽转人工。

INSERT INTO scheduled_task (task_key, task_name, task_group, schedule_desc, enabled, remark)
VALUES ('refund-query', '退款查单推进', 'FINANCE', '每 60 秒', true,
        '只读查询微信退款状态并推进 PROCESSING/本地收尾，不重发渠道退款')
ON CONFLICT (task_key) DO UPDATE
SET task_name = EXCLUDED.task_name,
    task_group = EXCLUDED.task_group,
    schedule_desc = EXCLUDED.schedule_desc,
    enabled = EXCLUDED.enabled,
    remark = EXCLUDED.remark,
    updated_at = now();

UPDATE scheduled_task
SET schedule_desc = '每 1 小时',
    remark = '每小时处理 REFUND_INITIATED 超时重发与 30 分钟耗尽转人工；微信 ABNORMAL 不自动重发',
    updated_at = now()
WHERE task_key = 'refund-retry';
