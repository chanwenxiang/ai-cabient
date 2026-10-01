-- P3-6 消费者小程序广告轮播位：投放端维度 + 跳转深链（见 docs/SYSTEM_COMPARISON_EASYGO_VS_AICABINET_2026-10-01.md §8.1）
-- channel: CABINET_SCREEN=柜机屏（默认，存量零影响）；MINI_PROGRAM=消费者小程序轮播位
ALTER TABLE ad_campaign ADD COLUMN IF NOT EXISTS channel VARCHAR(16) NOT NULL DEFAULT 'CABINET_SCREEN';
ALTER TABLE ad_campaign ADD COLUMN IF NOT EXISTS link_url VARCHAR(256);

CREATE INDEX IF NOT EXISTS idx_ad_campaign_channel ON ad_campaign (channel, status);
