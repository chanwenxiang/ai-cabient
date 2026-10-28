-- MIGRATION_KIND: backfill
-- 缺货自动建补货任务调度登记（AutoReplenishmentTaskService，CB-072 后续）。
-- ScheduledTaskRegistry.register("auto-replenish-task") 需登记行，否则运营台
-- 不可见/不可启停/执行记录无处落（V364 同因）。
-- 口径：按设置的阈值触发（台账 low_threshold / 货道 minLevel / 销量补货点），
-- 同柜已有 PENDING/IN_PROGRESS 任务即跳过；开关 replenishment.auto_task.enabled。
INSERT INTO scheduled_task (task_key, task_name, task_group, schedule_desc, remark) VALUES
('auto-replenish-task', '缺货自动建补货任务', 'WAREHOUSE', '每 30 分钟',
 '定时扫描柜机建议（fillToPar=false 阈值口径），有缺口且无 PENDING/IN_PROGRESS 任务时按建议生成路线 PLANNED + 任务 PENDING（有主仓自动生成出库行）；同柜已有进行中任务幂等跳过；开关 replenishment.auto_task.enabled 默认开')
ON CONFLICT (task_key) DO NOTHING;
