-- V84: 边缘计算能力

CREATE TABLE IF NOT EXISTS edge_device (
    edge_id BIGSERIAL PRIMARY KEY,
    device_id VARCHAR(32) NOT NULL UNIQUE,
    model_version VARCHAR(64),
    device_model VARCHAR(32) NOT NULL,
    cpu_cores INT NOT NULL,
    memory_mb INT NOT NULL,
    storage_gb INT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OFFLINE',
    last_sync_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_edge_device_status ON edge_device (status);

COMMENT ON TABLE edge_device IS '边缘设备表';

CREATE TABLE IF NOT EXISTS edge_inference_log (
    id BIGSERIAL PRIMARY KEY,
    device_id VARCHAR(32) NOT NULL,
    session_id VARCHAR(64),
    model_type VARCHAR(32) NOT NULL,
    model_name VARCHAR(64),
    inference_time_ms INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    inference_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    result_sku_id VARCHAR(64),
    confidence DECIMAL(5,2),
    error_message TEXT
);

CREATE INDEX IF NOT EXISTS idx_edge_inference_device ON edge_inference_log (device_id);
CREATE INDEX IF NOT EXISTS idx_edge_inference_time ON edge_inference_log (inference_at);

COMMENT ON TABLE edge_inference_log IS '边缘推理日志表';

CREATE TABLE IF NOT EXISTS edge_model_version (
    version_id BIGSERIAL PRIMARY KEY,
    model_name VARCHAR(64) NOT NULL,
    version VARCHAR(32) NOT NULL,
    download_url VARCHAR(200),
    checksum VARCHAR(64),
    size_bytes BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    released_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_edge_model_name ON edge_model_version (model_name);
CREATE UNIQUE INDEX IF NOT EXISTS idx_edge_model_version ON edge_model_version (model_name, version);

COMMENT ON TABLE edge_model_version IS '边缘模型版本表';
