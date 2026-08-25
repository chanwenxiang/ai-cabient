-- AI Cabinet：资金 / 对账类任务种子（XXL-JOB 3.4.x）
-- 首次初始化：挂到 xxl-job-mysql 的 docker-entrypoint-initdb.d（见 docker-compose.xxljob.yml）
-- 已有库可手动：
--   docker exec -i <xxl-mysql> mysql -uroot -pxxljob xxl_job < infra/xxl-job/seed_aicabinet_jobs.sql

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
(101, 10, '未付订单自动取消', now(), now(), 'aicabinet', '',
 'CRON', '0 0/15 * * * ?', 'DO_NOTHING', 'FAILOVER',
 'unpaidCancelJob', '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
(102, 10, '充值单自动取消', now(), now(), 'aicabinet', '',
 'CRON', '0 0/5 * * * ?', 'DO_NOTHING', 'FAILOVER',
 'rechargeCancelJob', '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
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
(107, 10, '数据一致性巡检', now(), now(), 'aicabinet', '',
 'CRON', '0 0/5 * * * ?', 'DO_NOTHING', 'FAILOVER',
 'dataConsistencyJob', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
(108, 10, '优惠券过期处理', now(), now(), 'aicabinet', '',
 'CRON', '0 0 2 * * ?', 'DO_NOTHING', 'FAILOVER',
 'couponExpireJob', '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
(109, 10, '积分过期管理', now(), now(), 'aicabinet', '',
 'CRON', '0 0 0/6 * * ?', 'DO_NOTHING', 'FAILOVER',
 'pointsExpiryJob', '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
(110, 10, '稳定在线自动解锁', now(), now(), 'aicabinet', '',
 'CRON', '0 0/5 * * * ?', 'DO_NOTHING', 'FAILOVER',
 'deviceStableOnlineAutoUnlockJob', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0),
(111, 10, '设备可用性KPI日快照', now(), now(), 'aicabinet', '',
 'CRON', '0 10 1 * * ?', 'DO_NOTHING', 'FAILOVER',
 'deviceAvailabilityKpiDailyJob', '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', 'GLUE代码初始化', now(), '', 1, 0, 0)
ON DUPLICATE KEY UPDATE
  `job_desc`=VALUES(`job_desc`),
  `schedule_conf`=VALUES(`schedule_conf`),
  `executor_handler`=VALUES(`executor_handler`),
  `update_time`=VALUES(`update_time`);
