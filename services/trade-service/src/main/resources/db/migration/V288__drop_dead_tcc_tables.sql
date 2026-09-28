-- L2-3：TCC/分布式事务死代码下线
-- MIGRATION_KIND: backfill
-- 依据：docs/THREE_END_FULL_REVIEW_2026-09-27.md §10.2 —— distributed_transaction /
-- transaction_step 自上线起无任何业务写入方（DB 恒 0 行），唯一活跃补偿语义是
-- 分账回退重试（compensation_task，保留）。本迁移删除死表与对应 scheduled_task 种子行；
-- live xxl_job_info 的 compensationRetryJob 由部署脚本同步移除。

DROP TABLE IF EXISTS transaction_step;
DROP TABLE IF EXISTS distributed_transaction;

DELETE FROM scheduled_task WHERE task_key = 'compensation-retry';

-- MIGRATION_KIND=backfill（纯数据/表清理，无新增列）
