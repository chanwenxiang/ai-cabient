-- V77: 加盟商管理

CREATE TABLE IF NOT EXISTS franchise (
    franchise_id BIGSERIAL PRIMARY KEY,
    franchise_name VARCHAR(100) NOT NULL,
    franchise_code VARCHAR(32) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    contact_name VARCHAR(100),
    contact_phone VARCHAR(32),
    address VARCHAR(200),
    province VARCHAR(64),
    city VARCHAR(64),
    district VARCHAR(64),
    commission_rate DECIMAL(10,4),
    deposit_amount DECIMAL(12,2),
    contract_number VARCHAR(64),
    contract_start_date TIMESTAMPTZ,
    contract_end_date TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_franchise_status ON franchise (status);
CREATE INDEX IF NOT EXISTS idx_franchise_city ON franchise (province, city);

COMMENT ON TABLE franchise IS '加盟商表';

CREATE TABLE IF NOT EXISTS franchise_device (
    id BIGSERIAL PRIMARY KEY,
    franchise_id BIGINT NOT NULL,
    device_id VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    unassigned_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_franchise_device_franchise ON franchise_device (franchise_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_franchise_device_active ON franchise_device (device_id) WHERE status = 'ACTIVE';

COMMENT ON TABLE franchise_device IS '加盟商设备关联表';

CREATE TABLE IF NOT EXISTS franchise_settlement (
    settlement_id BIGSERIAL PRIMARY KEY,
    franchise_id BIGINT NOT NULL,
    settlement_period VARCHAR(32) NOT NULL,
    gross_revenue DECIMAL(12,2) NOT NULL,
    commission_amount DECIMAL(12,2) NOT NULL,
    adjustment_amount DECIMAL(12,2),
    net_amount DECIMAL(12,2) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    settled_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_franchise_settlement_franchise ON franchise_settlement (franchise_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_franchise_settlement_period ON franchise_settlement (franchise_id, settlement_period);

COMMENT ON TABLE franchise_settlement IS '加盟商结算表';
