-- V80: 数据一致性管理

CREATE TABLE IF NOT EXISTS data_change_log (
    id BIGSERIAL PRIMARY KEY,
    table_name VARCHAR(64) NOT NULL,
    record_id VARCHAR(64) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    old_value JSONB,
    new_value JSONB,
    changed_by VARCHAR(64),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    verified_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_data_change_table ON data_change_log (table_name, record_id);
CREATE INDEX IF NOT EXISTS idx_data_change_time ON data_change_log (changed_at);
CREATE INDEX IF NOT EXISTS idx_data_change_verified ON data_change_log (verified) WHERE verified = FALSE;

COMMENT ON TABLE data_change_log IS '数据变更日志表';

CREATE TABLE IF NOT EXISTS data_consistency_record (
    id BIGSERIAL PRIMARY KEY,
    check_type VARCHAR(64) NOT NULL,
    table_name VARCHAR(64) NOT NULL,
    check_key VARCHAR(64),
    expected_value TEXT,
    actual_value TEXT,
    status VARCHAR(16) NOT NULL,
    error_message TEXT,
    checked_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    fixed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_consistency_status ON data_consistency_record (status);
CREATE INDEX IF NOT EXISTS idx_consistency_type ON data_consistency_record (check_type);
CREATE INDEX IF NOT EXISTS idx_consistency_time ON data_consistency_record (checked_at);

COMMENT ON TABLE data_consistency_record IS '数据一致性检查记录表';
