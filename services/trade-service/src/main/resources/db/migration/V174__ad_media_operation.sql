-- V102: 广告/多媒体运营：素材库 + 投放计划 + 设备屏内容
CREATE TABLE IF NOT EXISTS media_asset (
    asset_id         BIGSERIAL PRIMARY KEY,
    title            VARCHAR(128) NOT NULL,
    asset_type       VARCHAR(16) NOT NULL,             -- IMAGE | VIDEO | H5
    storage_uri      VARCHAR(512) NOT NULL,            -- minio://bucket/object
    duration_seconds INT NOT NULL DEFAULT 10,
    status           VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | INACTIVE
    uploaded_by      BIGINT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS ad_campaign (
    campaign_id BIGSERIAL PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'DRAFT',  -- DRAFT | RUNNING | STOPPED
    device_scope VARCHAR(32) NOT NULL DEFAULT 'ALL',   -- ALL | SPECIFIC
    start_at    TIMESTAMPTZ,
    end_at      TIMESTAMPTZ,
    created_by  BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS ad_campaign_item (
    item_id     BIGSERIAL PRIMARY KEY,
    campaign_id BIGINT NOT NULL REFERENCES ad_campaign(campaign_id) ON DELETE CASCADE,
    asset_id    BIGINT NOT NULL REFERENCES media_asset(asset_id),
    sort_order  INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS ad_campaign_device (
    id          BIGSERIAL PRIMARY KEY,
    campaign_id BIGINT NOT NULL REFERENCES ad_campaign(campaign_id) ON DELETE CASCADE,
    device_id   VARCHAR(64) NOT NULL,
    UNIQUE (campaign_id, device_id)
);
