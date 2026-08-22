-- low-pri: risk auto-disposition + ad play events + demo isolation config
ALTER TABLE risk_event
    ADD COLUMN IF NOT EXISTS disposition_status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    ADD COLUMN IF NOT EXISTS disposition_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS disposition_note VARCHAR(256);

CREATE INDEX IF NOT EXISTS idx_risk_event_disposition
    ON risk_event (disposition_status, created_at DESC);

CREATE TABLE IF NOT EXISTS ad_play_event (
    event_id     BIGSERIAL PRIMARY KEY,
    campaign_id  BIGINT NOT NULL,
    device_id    VARCHAR(64) NOT NULL,
    asset_id     BIGINT,
    event_type   VARCHAR(16) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_ad_play_campaign ON ad_play_event (campaign_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ad_play_device ON ad_play_event (device_id, created_at DESC);

INSERT INTO system_config (config_key, config_value, description, updated_at)
VALUES
  ('risk.auto_clear_info_hours', '72', 'INFO 级风控事件超过 N 小时自动结清（0=关闭）', NOW()),
  ('risk.auto_ack_warn_hours', '168', 'WARN 级风控事件超过 N 小时自动确认留痕（0=关闭）', NOW()),
  ('ops.demo_data_banner', 'true', '大屏/分析页在 mock 或 staging 时展示「演示数据」横幅', NOW())
ON CONFLICT (config_key) DO NOTHING;
