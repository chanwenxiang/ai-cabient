ALTER TABLE shopping_session
    ADD COLUMN IF NOT EXISTS replenishment_task_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_shopping_session_replenishment_task
    ON shopping_session (replenishment_task_id)
    WHERE replenishment_task_id IS NOT NULL;
