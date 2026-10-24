-- 自主补货 clientRequestNo 幂等契约（2026-10-23 审计残留 H2）：
-- 前端同一提交意图的超时重试复用同键，服务端按键幂等返回原任务；
-- 唯一索引兜住并发双击/重放（Postgres partial unique，NULL 不受限，存量行不受影响）。
ALTER TABLE replenishment_task ADD COLUMN IF NOT EXISTS client_request_no VARCHAR(64);
CREATE UNIQUE INDEX IF NOT EXISTS uk_replenishment_task_client_request_no
    ON replenishment_task (client_request_no) WHERE client_request_no IS NOT NULL;
