-- 补齐 scheduled_task 缺失的注册任务登记行（运营台可见性修复）
--
-- 背景：ScheduledTaskService.finish() 只更新**已存在**的行（findByIdForUpdate().orElse(null)），
-- 行不存在时执行记录被静默丢弃。因此「注册表里有、scheduled_task 里没有」的任务会进入隐形态：
-- 任务照跑，但运营台列表看不见、无法启停（requireTaskForUpdate 404）、无法手动触发、
-- 最近执行/耗时/结果无处落 —— 出问题时没有任何线索。V152 建表时只登记了 22 个任务，
-- 后续在 ScheduledTaskRegistry 新增的 7 个（含 XXL 托管的 points-expiry）从未补行。
--
-- 已由 scripts/check-scheduled-task-seed.mjs 静态守住，新增注册任务必须同时补种子行。
INSERT INTO scheduled_task (task_key, task_name, task_group, schedule_desc, remark) VALUES
('session-door-open-expire', '消费者开门超时清理', 'TRADE',     '每 60 秒',              '消费者购物态超时自动关闭'),
('points-expiry',            '积分过期管理',       'MARKETING', '每 6 小时',             '过期积分批次清零（XXL-JOB 托管）'),
('coupon-expiry-remind',     '优惠券临期提醒',     'MARKETING', '每 6 小时',             '临期券定向提醒推送'),
('growth-log-archive',       '增长日志归档',       'SYSTEM',    '每日 03:00',            '增长日志冷热分离归档'),
('sku-review-daily',         '选品诊断每日刷新',   'MARKETING', '每日 04:00',            '选品诊断结果每日重算'),
('risk-auto-disposition',    '风控事件自动处置',   'OPS',       '每 15 分钟',            '风控事件按策略自动处置'),
('temp-plan',                '温控计划下发',       'DEVICE',    '每 60 秒',              '温控计划到点下发至柜机'),
('scheduled-task-stale-monitor', '定时任务超期看护', 'SYSTEM',  '每 5 分钟',
 '以 scheduled_task.last_run_at 为判据，检测 XXL-JOB 托管任务是否停跑；刻意不列入托管清单')
ON CONFLICT (task_key) DO NOTHING;
