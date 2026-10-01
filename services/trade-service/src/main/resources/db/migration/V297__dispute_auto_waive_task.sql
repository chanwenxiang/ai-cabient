-- MIGRATION_KIND: backfill
-- P3-4 争议超时自动免单（docs/P3_4_DISPUTE_AUTO_WAIVE_DESIGN.md）。
-- 默认 OFF（aicabinet.dispute-auto-waive.enabled=false，fail-closed），dev 先开观察。
INSERT INTO scheduled_task (task_key, task_name, task_group, schedule_desc, remark) VALUES
('dispute-auto-waive', '争议超时自动免单', 'OPS', '每 15 分钟',
 '超时未认领争议单超阈值自动免单结案（零资金移动纯收口；门控与防薅见设计稿）')
ON CONFLICT (task_key) DO NOTHING;
