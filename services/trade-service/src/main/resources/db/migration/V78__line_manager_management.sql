-- V78: 线长管理

CREATE TABLE IF NOT EXISTS line_manager (
    manager_id BIGSERIAL PRIMARY KEY,
    manager_name VARCHAR(100) NOT NULL,
    phone VARCHAR(32) NOT NULL,
    employee_id VARCHAR(64),
    franchise_id BIGINT NOT NULL,
    commission_rate DECIMAL(10,4),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_line_manager_franchise ON line_manager (franchise_id);
CREATE INDEX IF NOT EXISTS idx_line_manager_phone ON line_manager (phone);
CREATE UNIQUE INDEX IF NOT EXISTS idx_line_manager_employee ON line_manager (employee_id) WHERE employee_id IS NOT NULL;

COMMENT ON TABLE line_manager IS '线长表';

CREATE TABLE IF NOT EXISTS line_device (
    id BIGSERIAL PRIMARY KEY,
    manager_id BIGINT NOT NULL,
    device_id VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    unassigned_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_line_device_manager ON line_device (manager_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_line_device_active ON line_device (device_id) WHERE status = 'ACTIVE';

COMMENT ON TABLE line_device IS '线长设备关联表';

CREATE TABLE IF NOT EXISTS line_manager_settlement (
    settlement_id BIGSERIAL PRIMARY KEY,
    manager_id BIGINT NOT NULL,
    settlement_period VARCHAR(32) NOT NULL,
    gross_revenue DECIMAL(12,2) NOT NULL,
    commission_amount DECIMAL(12,2) NOT NULL,
    adjustment_amount DECIMAL(12,2),
    net_amount DECIMAL(12,2) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    settled_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_line_settlement_manager ON line_manager_settlement (manager_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_line_settlement_period ON line_manager_settlement (manager_id, settlement_period);

COMMENT ON TABLE line_manager_settlement IS '线长结算表';
