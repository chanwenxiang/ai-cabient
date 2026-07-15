-- V74: 微信支付分与免密支付

CREATE TABLE IF NOT EXISTS payscore_contract (
    contract_id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES user_info(user_id),
    open_id VARCHAR(64) NOT NULL,
    contract_code VARCHAR(64) NOT NULL,
    contract_state VARCHAR(16) NOT NULL,
    contract_signed_time TIMESTAMPTZ,
    contract_expired_time TIMESTAMPTZ,
    contract_terminated_time TIMESTAMPTZ,
    cancel_reason VARCHAR(256),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_payscore_user ON payscore_contract (user_id);
CREATE INDEX IF NOT EXISTS idx_payscore_openid ON payscore_contract (open_id);

COMMENT ON TABLE payscore_contract IS '微信支付分签约记录表';

CREATE TABLE IF NOT EXISTS payscore_order (
    payscore_order_id VARCHAR(64) PRIMARY KEY,
    order_id VARCHAR(32) NOT NULL REFERENCES cabinet_order(order_id),
    user_id BIGINT NOT NULL,
    open_id VARCHAR(64) NOT NULL,
    service_id VARCHAR(64) NOT NULL,
    out_order_no VARCHAR(64) NOT NULL,
    service_start_time TIMESTAMPTZ,
    service_end_time TIMESTAMPTZ,
    total_amount_cents INT NOT NULL,
    actual_amount_cents INT,
    order_state VARCHAR(16) NOT NULL,
    need_user_confirm BOOLEAN DEFAULT FALSE,
    need_collection BOOLEAN DEFAULT FALSE,
    collection_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_payscore_order_user ON payscore_order (user_id);

CREATE TABLE IF NOT EXISTS payment_risk_config (
    config_id BIGSERIAL PRIMARY KEY,
    config_type VARCHAR(32) NOT NULL,
    target_type VARCHAR(16) NOT NULL,
    target_value VARCHAR(128) NOT NULL,
    limit_value INT,
    period_seconds INT,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_payment_risk_type ON payment_risk_config (config_type, target_type);

INSERT INTO payment_risk_config (config_type, target_type, target_value, limit_value, period_seconds, status)
VALUES 
    ('LIMIT', 'USER', 'daily_amount', 50000, 86400, 'ACTIVE'),
    ('LIMIT', 'USER', 'single_amount', 5000, 0, 'ACTIVE'),
    ('LIMIT', 'DEVICE', 'daily_count', 20, 86400, 'ACTIVE')
ON CONFLICT DO NOTHING;
