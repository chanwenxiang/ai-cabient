-- 分账回退等轻量补偿任务：不强制绑定 distributed_transaction

ALTER TABLE compensation_task DROP CONSTRAINT IF EXISTS compensation_task_tx_id_fkey;

CREATE UNIQUE INDEX IF NOT EXISTS uq_compensation_task_biz_pending
    ON compensation_task (tx_id, task_type)
    WHERE status = 'PENDING';
