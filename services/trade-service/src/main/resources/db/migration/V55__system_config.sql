-- 消费者端可配置项（客服电话等）
CREATE TABLE IF NOT EXISTS system_config (
    config_key   VARCHAR(64) PRIMARY KEY,
    config_value TEXT        NOT NULL,
    description  VARCHAR(256),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO system_config (config_key, config_value, description)
VALUES ('consumer.service_phone', '400-888-0018', '消费者客服电话')
ON CONFLICT (config_key) DO NOTHING;
