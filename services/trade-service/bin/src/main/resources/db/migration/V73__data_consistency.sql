-- V73: 数据变更日志表

CREATE TABLE IF NOT EXISTS data_change_log (
    log_id BIGSERIAL PRIMARY KEY,
    table_name VARCHAR(64) NOT NULL,
    record_id VARCHAR(64) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    old_values JSONB,
    new_values JSONB,
    changed_fields TEXT[],
    operator_id BIGINT,
    operator_ip VARCHAR(45),
    operator_type VARCHAR(16),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_change_log_table_record ON data_change_log (table_name, record_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_change_log_operator ON data_change_log (operator_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_change_log_time ON data_change_log (created_at DESC);

COMMENT ON TABLE data_change_log IS '数据变更日志表：记录所有关键数据的变更历史';

CREATE TABLE IF NOT EXISTS data_quality_check (
    check_id BIGSERIAL PRIMARY KEY,
    check_type VARCHAR(32) NOT NULL,
    check_name VARCHAR(128) NOT NULL,
    table_name VARCHAR(64) NOT NULL,
    check_rule VARCHAR(256) NOT NULL,
    expected_result TEXT,
    actual_result TEXT,
    is_passed BOOLEAN NOT NULL,
    error_count INT DEFAULT 0,
    checked_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    next_check_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_quality_check_time ON data_quality_check (checked_at DESC);

CREATE TABLE IF NOT EXISTS data_discrepancy (
    discrepancy_id BIGSERIAL PRIMARY KEY,
    discrepancy_type VARCHAR(32) NOT NULL,
    table1_name VARCHAR(64) NOT NULL,
    table1_key VARCHAR(64),
    table1_value JSONB,
    table2_name VARCHAR(64) NOT NULL,
    table2_key VARCHAR(64),
    table2_value JSONB,
    expected_value JSONB,
    severity VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    resolved_by BIGINT,
    resolved_at TIMESTAMPTZ,
    resolution_notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_discrepancy_status ON data_discrepancy (status, severity, created_at DESC);
