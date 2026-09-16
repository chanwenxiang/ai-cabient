-- AI Cabinet：业务定时任务种子（XXL-JOB 3.4.x）
--
-- 范围：**全部业务定时任务**（2026-09-16 起全量托管，生产为多实例部署，由调度中心单选派发；
--   见 XxlJobManagedTasks.KEYS）。刻意排除 2 个：scheduled-task-stale-monitor（超期看护，
--   必须留在 Spring 才能检测调度中心故障）与 cache-purge（本机缓存清理，每实例自清、不进运营台）。
--
-- 时区约定：Asia/Shanghai（与 aicabinet.schedule.zone / ScheduleZones 一致；
--   admin MySQL URL 已带 serverTimezone=Asia/Shanghai）
-- 首次初始化：挂到 xxl-job-mysql 的 docker-entrypoint-initdb.d（见 docker-compose.xxljob.yml）
-- 已有库升级（新增排期即需重跑，幂等）：
--   docker exec -i <xxl-mysql> mysql -uroot -pxxljob xxl_job < infra/xxl-job/seed_aicabinet_jobs.sql
--
-- 路由：FAILOVER（多实例下单选一台执行，失败的自动转下一台）；阻塞：SERIAL_EXECUTION。
-- executor_fail_retry_count：只读巡检类 0，写型/资金/通知类 1~2。
-- cron 必须与 ScheduleZones.XXL_CRON_BY_TASK 一致，由 scripts/check-xxl-job-wiring.mjs 静态校验。

INSERT INTO `xxl_job_group`(`id`, `app_name`, `title`, `address_type`, `address_list`, `update_time`)
VALUES (10, 'trade-service', 'AI Cabinet trade-service', 0, NULL, now())
ON DUPLICATE KEY UPDATE `app_name`=VALUES(`app_name`), `title`=VALUES(`title`), `update_time`=VALUES(`update_time`);

INSERT INTO `xxl_job_info`(
  `id`, `job_group`, `job_desc`, `add_time`, `update_time`, `author`, `alarm_email`,
  `schedule_type`, `schedule_conf`, `misfire_strategy`, `executor_route_strategy`,
  `executor_handler`, `executor_param`, `executor_block_strategy`, `executor_timeout`,
  `executor_fail_retry_count`, `glue_type`, `glue_source`, `glue_remark`, `glue_updatetime`,
  `child_jobid`, `trigger_status`, `trigger_last_time`, `trigger_next_time`
) VALUES
-- ── 交易会话与订单 ──────────────────────────────────────────────────────
(101, 10, '未付订单自动取消', now(), now(), 'aicabinet', '',
 'CRON', '0 0/15 * * * ?', 'DO_NOTHING', 'FAILOVER',
 'unpaidCancelJob', '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
(102, 10, '充值单自动取消', now(), now(), 'aicabinet', '',
 'CRON', '0 0/5 * * * ?', 'DO_NOTHING', 'FAILOVER',
 'rechargeCancelJob', '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
(112, 10, '开门超时会话清理', now(), now(), 'aicabinet', '',
 'CRON', '0/30 * * * * ?', 'DO_NOTHING', 'FAILOVER',
 'sessionOpeningExpireJob', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
(113, 10, '补货会话超时清理', now(), now(), 'aicabinet', '',
 'CRON', '0 0/1 * * * ?', 'DO_NOTHING', 'FAILOVER',
 'sessionRestockExpireJob', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
(114, 10, '消费者开门超时清理', now(), now(), 'aicabinet', '',
 'CRON', '0 0/1 * * * ?', 'DO_NOTHING', 'FAILOVER',
 'sessionDoorOpenExpireJob', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
(115, 10, '识别结算超时升级', now(), now(), 'aicabinet', '',
 'CRON', '0 0/1 * * * ?', 'DO_NOTHING', 'FAILOVER',
 'sessionRecognizingExpireJob', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
-- ── 设备 ────────────────────────────────────────────────────────────────
(116, 10, '设备离线巡检', now(), now(), 'aicabinet', '',
 'CRON', '0 0/1 * * * ?', 'DO_NOTHING', 'FAILOVER',
 'devicePresenceOfflineJob', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
(110, 10, '稳定在线自动解锁', now(), now(), 'aicabinet', '',
 'CRON', '0 0/5 * * * ?', 'DO_NOTHING', 'FAILOVER',
 'deviceStableOnlineAutoUnlockJob', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
(118, 10, '温控计划下发', now(), now(), 'aicabinet', '',
 'CRON', '0 0/1 * * * ?', 'DO_NOTHING', 'FAILOVER',
 'tempPlanApplyJob', '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
-- ── 资金 / 财务 ─────────────────────────────────────────────────────────
(103, 10, '分账重试', now(), now(), 'aicabinet', '',
 'CRON', '0 0/15 * * * ?', 'DO_NOTHING', 'FAILOVER',
 'profitSharingRetryJob', '', 'SERIAL_EXECUTION', 0, 2, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
(104, 10, '每日对账', now(), now(), 'aicabinet', '',
 'CRON', '0 30 1 * * ?', 'DO_NOTHING', 'FAILOVER',
 'reconciliationJob', '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
(105, 10, '线长佣金入账', now(), now(), 'aicabinet', '',
 'CRON', '0 20 0 * * ?', 'DO_NOTHING', 'FAILOVER',
 'lineCommissionJob', '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
(106, 10, '财务保证金固化', now(), now(), 'aicabinet', '',
 'CRON', '0 5 0 * * ?', 'DO_NOTHING', 'FAILOVER',
 'financeMarginJob', '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
(119, 10, '周期费用月结出账', now(), now(), 'aicabinet', '',
 'CRON', '0 30 1 1 * ?', 'DO_NOTHING', 'FAILOVER',
 'opsFeeBillMonthlyJob', '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
-- ── 营销 ────────────────────────────────────────────────────────────────
(108, 10, '优惠券过期处理', now(), now(), 'aicabinet', '',
 'CRON', '0 0 2 * * ?', 'DO_NOTHING', 'FAILOVER',
 'couponExpireJob', '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
(120, 10, '优惠券临期提醒', now(), now(), 'aicabinet', '',
 'CRON', '0 0 0/6 * * ?', 'DO_NOTHING', 'FAILOVER',
 'couponExpiryRemindJob', '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
(109, 10, '积分过期管理', now(), now(), 'aicabinet', '',
 'CRON', '0 0 0/6 * * ?', 'DO_NOTHING', 'FAILOVER',
 'pointsExpiryJob', '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
(121, 10, '选品诊断每日刷新', now(), now(), 'aicabinet', '',
 'CRON', '0 0 4 * * ?', 'DO_NOTHING', 'FAILOVER',
 'skuReviewDailyJob', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
-- ── 运维 / 系统 ─────────────────────────────────────────────────────────
(107, 10, '数据一致性巡检', now(), now(), 'aicabinet', '',
 'CRON', '0 0/5 * * * ?', 'DO_NOTHING', 'FAILOVER',
 'dataConsistencyJob', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
(122, 10, '异常卡点扫描', now(), now(), 'aicabinet', '',
 'CRON', '0/30 * * * * ?', 'DO_NOTHING', 'FAILOVER',
 'opsExceptionScannerJob', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
(123, 10, '补偿任务处理', now(), now(), 'aicabinet', '',
 'CRON', '0/30 * * * * ?', 'DO_NOTHING', 'FAILOVER',
 'compensationProcessJob', '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
(124, 10, '补偿任务重试', now(), now(), 'aicabinet', '',
 'CRON', '0 0/1 * * * ?', 'DO_NOTHING', 'FAILOVER',
 'compensationRetryJob', '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
(125, 10, '商户工作台通知', now(), now(), 'aicabinet', '',
 'CRON', '0 0/15 * * * ?', 'DO_NOTHING', 'FAILOVER',
 'merchantNotifyJob', '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
(126, 10, '争议 SLA 巡检', now(), now(), 'aicabinet', '',
 'CRON', '0 0/15 * * * ?', 'DO_NOTHING', 'FAILOVER',
 'disputeSlaJob', '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
(127, 10, '补货超时收口', now(), now(), 'aicabinet', '',
 'CRON', '0 0/1 * * * ?', 'DO_NOTHING', 'FAILOVER',
 'replenishmentTimeoutJob', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
(128, 10, '库存临期预警', now(), now(), 'aicabinet', '',
 'CRON', '0 0 * * * ?', 'DO_NOTHING', 'FAILOVER',
 'expiryAlertJob', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
(129, 10, '增长日志归档', now(), now(), 'aicabinet', '',
 'CRON', '0 0 3 * * ?', 'DO_NOTHING', 'FAILOVER',
 'growthLogArchiveJob', '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
(130, 10, 'SLA 日快照', now(), now(), 'aicabinet', '',
 'CRON', '0 5 0 * * ?', 'DO_NOTHING', 'FAILOVER',
 'slaSnapshotJob', '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
(111, 10, '设备可用性 KPI 日快照', now(), now(), 'aicabinet', '',
 'CRON', '0 10 1 * * ?', 'DO_NOTHING', 'FAILOVER',
 'deviceAvailabilityKpiDailyJob', '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
(131, 10, '风控事件自动处置', now(), now(), 'aicabinet', '',
 'CRON', '0 0/15 * * * ?', 'DO_NOTHING', 'FAILOVER',
 'riskAutoDispositionJob', '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0)
ON DUPLICATE KEY UPDATE
  `job_desc`=VALUES(`job_desc`),
  `schedule_conf`=VALUES(`schedule_conf`),
  `executor_handler`=VALUES(`executor_handler`),
  `executor_param`=VALUES(`executor_param`),
  `executor_route_strategy`=VALUES(`executor_route_strategy`),
  `executor_block_strategy`=VALUES(`executor_block_strategy`),
  `executor_fail_retry_count`=VALUES(`executor_fail_retry_count`),
  `update_time`=VALUES(`update_time`);
