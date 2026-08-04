-- V10: 并发控制相关表

-- 幂等性控制表
CREATE TABLE IF NOT EXISTS idempotency_key (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    business_type VARCHAR(32) NOT NULL,
    business_id VARCHAR(64) NOT NULL,
    request_hash VARCHAR(64),
    response_data JSONB,
    expire_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_idempotency_expire ON idempotency_key (expire_at);
CREATE INDEX IF NOT EXISTS idx_idempotency_business ON idempotency_key (business_type, business_id);

-- 限流配置表
CREATE TABLE IF NOT EXISTS rate_limit_config (
    config_id BIGSERIAL PRIMARY KEY,
    limit_type VARCHAR(32) NOT NULL,
    limit_key VARCHAR(128) NOT NULL,
    limit_value INT NOT NULL,
    period_seconds INT NOT NULL,
    strategy VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (limit_type, limit_key)
);

-- 限流记录表
CREATE TABLE IF NOT EXISTS rate_limit_record (
    record_id BIGSERIAL PRIMARY KEY,
    limit_type VARCHAR(32) NOT NULL,
    limit_key VARCHAR(128) NOT NULL,
    request_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    is_allowed BOOLEAN NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_rate_limit_record_time ON rate_limit_record (limit_type, limit_key, request_time DESC);

-- 插入默认限流配置
INSERT INTO rate_limit_config (limit_type, limit_key, limit_value, period_seconds, strategy, status)
VALUES 
    ('API', '/api/v2/sessions', 100, 60, 'SLIDING_WINDOW', 'ACTIVE'),
    ('API', '/api/v2/orders', 200, 60, 'SLIDING_WINDOW', 'ACTIVE'),
    ('USER', 'create_session', 10, 60, 'SLIDING_WINDOW', 'ACTIVE'),
    ('DEVICE', 'open_door', 5, 60, 'SLIDING_WINDOW', 'ACTIVE'),
    ('IP', 'api_requests', 1000, 60, 'SLIDING_WINDOW', 'ACTIVE')
ON CONFLICT (limit_type, limit_key) DO NOTHING;
