-- 补齐 scheduled_task 登记行：withdraw-paying-timeout（提现打款超时兜底 / H38）
--
-- 背景：ReconciliationScheduler.failStalePayingWithdraws() 用 @Scheduled(fixedDelay=600_000)
-- 每 10 分钟扫描「PAYING 且 updatedAt 超过 1 小时」的提现单并置 FAILED + 解冻。但它**从未登记**：
--   · scheduled_task 里没有行 → finish() 的 findByIdForUpdate().orElse(null) 取到 null，
--     执行记录被**静默丢弃**（不抛错、不打日志）；
--   · 于是运营台列表看不见它、无法启停（requireTaskForUpdate 404）、无法手动触发，
--     最近执行/耗时/结果全部无处落 —— 出问题时没有任何线索。
-- 与 V275 修的 7 个「隐形态」任务同族，由 scripts/check-scheduled-task-seed.mjs 规则四
-- （tryBegin 调用点必须已登记）静态拦下。本次同时把它纳入 XXL 托管清单
-- （XxlJobManagedTasks.KEYS + 具名 handler + ScheduleZones 的 cron/静默阈值 + XXL 排期行），
-- 让多实例部署下由调度中心单选派发。
INSERT INTO scheduled_task (task_key, task_name, task_group, schedule_desc, remark) VALUES
('withdraw-paying-timeout', '提现打款超时兜底', 'FINANCE', '每 10 分钟',
 'PAYING 超 1 小时的提现单置 FAILED 并解冻（商户/线长），之后可人工重试打款（XXL-JOB 托管）')
ON CONFLICT (task_key) DO NOTHING;
