-- MIGRATION_KIND: backfill
-- 审计 P2-4：FAILED 会话预授权悬挂清扫（SessionExpireService.releaseStaleFailedSessionHolds）。
-- 与 V305（cabinet_order.session_id 唯一索引）同批落地，种子行保证运营台可见/可启停。
INSERT INTO scheduled_task (task_key, task_name, task_group, schedule_desc, remark) VALUES
('session-failed-hold-clear', 'FAILED 会话预授权悬挂清扫', 'TRADE', '每 5 分钟',
 'FAILED 且预授权仍 FROZEN 滞留超 1 小时的会话自动释放冻结并报 HIGH 异常留痕（结算失败路径漏释放的兜底，审计 P2-4）')
ON CONFLICT (task_key) DO NOTHING;
