-- 生产增强：对账明细、OTA 灰度/CDN、设备坐标、补货路线规划

-- OTA 灰度 + 对象存储 URI（CDN 预签名）
ALTER TABLE ota_release ADD COLUMN IF NOT EXISTS object_storage_uri VARCHAR(512);
ALTER TABLE ota_release ADD COLUMN IF NOT EXISTS gray_percent INT NOT NULL DEFAULT 100;
ALTER TABLE ota_release ADD COLUMN IF NOT EXISTS device_allowlist JSONB;
ALTER TABLE ota_release ADD COLUMN IF NOT EXISTS presign_ttl_seconds INT NOT NULL DEFAULT 3600;

-- 设备地理坐标（补货路线规划）
ALTER TABLE device_info ADD COLUMN IF NOT EXISTS latitude DOUBLE PRECISION;
ALTER TABLE device_info ADD COLUMN IF NOT EXISTS longitude DOUBLE PRECISION;
ALTER TABLE device_info ADD COLUMN IF NOT EXISTS address VARCHAR(256);

-- 补货路线：距离与 GeoJSON 轨迹
ALTER TABLE replenishment_route ADD COLUMN IF NOT EXISTS total_distance_m INT;
ALTER TABLE replenishment_route ADD COLUMN IF NOT EXISTS route_geo_json JSONB;
ALTER TABLE replenishment_route ADD COLUMN IF NOT EXISTS start_latitude DOUBLE PRECISION;
ALTER TABLE replenishment_route ADD COLUMN IF NOT EXISTS start_longitude DOUBLE PRECISION;

-- 对账平台账单行（拉取后落库，便于审计）
CREATE TABLE IF NOT EXISTS payment_platform_bill_line (
    line_id           BIGSERIAL    PRIMARY KEY,
    recon_id          BIGINT       REFERENCES payment_reconciliation(recon_id) ON DELETE CASCADE,
    channel           VARCHAR(16)  NOT NULL,
    platform_trade_no VARCHAR(64)  NOT NULL,
    merchant_order_no VARCHAR(64),
    amount_cents      BIGINT       NOT NULL,
    trade_time        TIMESTAMPTZ,
    trade_type        VARCHAR(32),
    matched           BOOLEAN      NOT NULL DEFAULT FALSE,
    raw_detail        JSONB,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_platform_bill_recon ON payment_platform_bill_line (recon_id);
CREATE INDEX idx_platform_bill_merchant ON payment_platform_bill_line (merchant_order_no);

-- 演示：CAB-001 坐标（上海附近）
UPDATE device_info SET latitude = 31.2304, longitude = 121.4737, address = '演示点位 A'
WHERE device_id = 'CAB-001' AND latitude IS NULL;
