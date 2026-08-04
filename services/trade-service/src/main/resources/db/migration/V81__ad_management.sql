-- V81: 广告位管理

CREATE TABLE IF NOT EXISTS ad_slot (
    slot_id BIGSERIAL PRIMARY KEY,
    slot_name VARCHAR(100) NOT NULL,
    slot_code VARCHAR(32) NOT NULL UNIQUE,
    slot_type VARCHAR(32) NOT NULL,
    device_id VARCHAR(32),
    position VARCHAR(64),
    width INT NOT NULL,
    height INT NOT NULL,
    default_price DECIMAL(10,2),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_ad_slot_status ON ad_slot (status);
CREATE INDEX IF NOT EXISTS idx_ad_slot_device ON ad_slot (device_id);

COMMENT ON TABLE ad_slot IS '广告位表';

CREATE TABLE IF NOT EXISTS ad_campaign (
    campaign_id BIGSERIAL PRIMARY KEY,
    campaign_name VARCHAR(100) NOT NULL,
    campaign_type VARCHAR(32) NOT NULL,
    advertiser_id VARCHAR(64),
    image_url VARCHAR(200),
    target_url VARCHAR(256),
    slot_id BIGINT NOT NULL,
    budget DECIMAL(12,2),
    spent DECIMAL(12,2) NOT NULL DEFAULT 0,
    start_time TIMESTAMPTZ NOT NULL,
    end_time TIMESTAMPTZ,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    priority INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_ad_campaign_slot ON ad_campaign (slot_id);
CREATE INDEX IF NOT EXISTS idx_ad_campaign_status ON ad_campaign (status);

COMMENT ON TABLE ad_campaign IS '广告活动表';

CREATE TABLE IF NOT EXISTS ad_impression (
    id BIGSERIAL PRIMARY KEY,
    campaign_id BIGINT NOT NULL,
    slot_id BIGINT NOT NULL,
    device_id VARCHAR(32),
    user_id VARCHAR(64),
    event_type VARCHAR(16) NOT NULL,
    cost DECIMAL(10,4),
    event_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    session_id VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_ad_impression_campaign ON ad_impression (campaign_id);
CREATE INDEX IF NOT EXISTS idx_ad_impression_time ON ad_impression (event_time);

COMMENT ON TABLE ad_impression IS '广告曝光点击记录表';
