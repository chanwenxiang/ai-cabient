-- 补偿任务重试计数（分账回退等轻量任务）

ALTER TABLE compensation_task
    ADD COLUMN IF NOT EXISTS retry_count INT NOT NULL DEFAULT 0;
