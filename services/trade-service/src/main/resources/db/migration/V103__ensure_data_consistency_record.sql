-- 补建数据一致性检查记录表（V80 可能在部分环境已标记成功但未含此表）
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
