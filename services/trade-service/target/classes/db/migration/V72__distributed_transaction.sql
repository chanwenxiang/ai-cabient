-- V72: 分布式事务补偿表

CREATE TABLE IF NOT EXISTS distributed_transaction (
    tx_id VARCHAR(64) PRIMARY KEY,
    tx_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    max_retry INT NOT NULL DEFAULT 5,
    payload JSONB NOT NULL,
    compensation_sql TEXT,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_distributed_tx_status ON distributed_transaction (status, created_at);
CREATE INDEX IF NOT EXISTS idx_distributed_tx_type ON distributed_transaction (tx_type, created_at DESC);

COMMENT ON TABLE distributed_transaction IS '分布式事务表：记录跨服务调用的事务状态';

CREATE TABLE IF NOT EXISTS transaction_step (
    step_id BIGSERIAL PRIMARY KEY,
    tx_id VARCHAR(64) NOT NULL REFERENCES distributed_transaction(tx_id) ON DELETE CASCADE,
    step_order INT NOT NULL,
    step_name VARCHAR(64) NOT NULL,
    step_type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    request_data JSONB,
    response_data JSONB,
    error_message TEXT,
    executed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_transaction_step_tx ON transaction_step (tx_id, step_order);

CREATE TABLE IF NOT EXISTS compensation_task (
    task_id BIGSERIAL PRIMARY KEY,
    tx_id VARCHAR(64) NOT NULL REFERENCES distributed_transaction(tx_id) ON DELETE CASCADE,
    task_type VARCHAR(32) NOT NULL,
    priority INT NOT NULL DEFAULT 0,
    scheduled_at TIMESTAMPTZ NOT NULL,
    executed_at TIMESTAMPTZ,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    result TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_compensation_task_status ON compensation_task (status, scheduled_at);
