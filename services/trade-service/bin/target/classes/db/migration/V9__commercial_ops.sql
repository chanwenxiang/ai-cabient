-- 商业运营：OTA / 断网续传 / 多摄 / 风控 / 对账 / 补货 / SLA

-- 会话扩展：断网续传 + 多摄像头
ALTER TABLE shopping_session ADD COLUMN IF NOT EXISTS upload_status VARCHAR(24) NOT NULL DEFAULT 'NONE';
ALTER TABLE shopping_session ADD COLUMN IF NOT EXISTS video_clips JSONB;
ALTER TABLE shopping_session ADD COLUMN IF NOT EXISTS camera_fusion_mode VARCHAR(16) NOT NULL DEFAULT 'SINGLE';

CREATE INDEX IF NOT EXISTS idx_session_upload_status ON shopping_session (upload_status);

-- 设备 OTA 发布包
CREATE TABLE IF NOT EXISTS ota_release (
    release_id      BIGSERIAL    PRIMARY KEY,
    app_version     VARCHAR(32)  NOT NULL,
    channel         VARCHAR(32)  NOT NULL DEFAULT 'stable',
    download_url    VARCHAR(512) NOT NULL,
    checksum_sha256 VARCHAR(64),
    release_notes   TEXT,
    mandatory       BOOLEAN      NOT NULL DEFAULT FALSE,
    min_version     VARCHAR(32),
    status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    published_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_ota_version_channel ON ota_release (app_version, channel);

CREATE TABLE IF NOT EXISTS ota_device_report (
    device_id       VARCHAR(64)  NOT NULL,
    app_version     VARCHAR(32)  NOT NULL,
    reported_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (device_id)
);

-- 风控
CREATE TABLE IF NOT EXISTS user_blacklist (
    user_id     BIGINT       PRIMARY KEY,
    reason      VARCHAR(256) NOT NULL,
    source      VARCHAR(32)  NOT NULL DEFAULT 'MANUAL',
    expires_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS risk_event (
    event_id    BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT,
    device_id   VARCHAR(64),
    event_type  VARCHAR(32)  NOT NULL,
    severity    VARCHAR(16)  NOT NULL DEFAULT 'WARN',
    detail      JSONB,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_risk_event_user ON risk_event (user_id);
CREATE INDEX idx_risk_event_device ON risk_event (device_id);
CREATE INDEX idx_risk_event_type ON risk_event (event_type);

-- 支付日对账
CREATE TABLE IF NOT EXISTS payment_reconciliation (
    recon_id         BIGSERIAL    PRIMARY KEY,
    recon_date       DATE         NOT NULL,
    channel          VARCHAR(16)  NOT NULL,
    platform_total   BIGINT       NOT NULL DEFAULT 0,
    ledger_total     BIGINT       NOT NULL DEFAULT 0,
    diff_cents       BIGINT       NOT NULL DEFAULT 0,
    matched_count    INT          NOT NULL DEFAULT 0,
    unmatched_count  INT          NOT NULL DEFAULT 0,
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    detail           JSONB,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at     TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_recon_date_channel ON payment_reconciliation (recon_date, channel);

-- 补货：柜内库存 + 路线 + 任务
CREATE TABLE IF NOT EXISTS device_sku_inventory (
    device_id   VARCHAR(64)  NOT NULL,
    sku_id      VARCHAR(32)  NOT NULL,
    quantity    INT          NOT NULL DEFAULT 0,
    capacity    INT          NOT NULL DEFAULT 0,
    low_threshold INT        NOT NULL DEFAULT 2,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (device_id, sku_id)
);

CREATE TABLE IF NOT EXISTS replenishment_route (
    route_id          BIGSERIAL    PRIMARY KEY,
    route_name        VARCHAR(128) NOT NULL,
    assignee_user_id  BIGINT,
    planned_date      DATE         NOT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'PLANNED',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS replenishment_task (
    task_id     BIGSERIAL    PRIMARY KEY,
    route_id    BIGINT       REFERENCES replenishment_route(route_id),
    device_id   VARCHAR(64)  NOT NULL,
    assignee_user_id BIGINT,
    status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    notes       VARCHAR(256),
    completed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_replenishment_task_device ON replenishment_task (device_id);
CREATE INDEX idx_replenishment_task_route ON replenishment_task (route_id);

-- SLA 日快照
CREATE TABLE IF NOT EXISTS sla_daily_snapshot (
    snapshot_date          DATE    PRIMARY KEY,
    door_open_attempts     INT     NOT NULL DEFAULT 0,
    door_open_success      INT     NOT NULL DEFAULT 0,
    door_success_rate      REAL,
    avg_recognize_ms       BIGINT,
    p95_recognize_ms       BIGINT,
    device_total           INT     NOT NULL DEFAULT 0,
    device_online_peak     INT     NOT NULL DEFAULT 0,
    device_online_rate     REAL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 设备固件/App 版本（心跳上报）
ALTER TABLE device_info ADD COLUMN IF NOT EXISTS app_version VARCHAR(32);
ALTER TABLE device_info ADD COLUMN IF NOT EXISTS firmware_version VARCHAR(32);

-- 演示 OTA 包
INSERT INTO ota_release (app_version, channel, download_url, checksum_sha256, release_notes, mandatory, status, published_at)
VALUES ('1.0.0', 'stable', 'https://cdn.example.com/cabinet/app-1.0.0.apk', 'demo-sha256', '初始版本', FALSE, 'PUBLISHED', NOW())
ON CONFLICT (app_version, channel) DO NOTHING;
