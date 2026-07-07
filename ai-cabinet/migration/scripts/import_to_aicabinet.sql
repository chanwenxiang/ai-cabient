-- 写入 ai-cabinet PostgreSQL
-- 前置：将 export_*.sql 结果导出为 CSV，放到 staging 表或 \copy 导入

-- ========== 1. 临时 staging 表（按需执行） ==========

CREATE TABLE IF NOT EXISTS staging_users (
    user_id       BIGINT,
    phone_number  VARCHAR(32),
    name          VARCHAR(64),
    verified      SMALLINT,
    balance_cents INT
);

CREATE TABLE IF NOT EXISTS staging_devices (
    machine_id     BIGINT,
    machine_code   VARCHAR(64),
    machine_name   VARCHAR(128),
    online_status  VARCHAR(16)
);

CREATE TABLE IF NOT EXISTS staging_skus (
    sku_id         VARCHAR(64),
    sku_name       VARCHAR(128),
    price_cents    INT,
    weight_grams   INT
);

-- CSV 导入示例（psql）:
-- \copy staging_users FROM 'users.csv' CSV HEADER
-- \copy staging_devices FROM 'devices.csv' CSV HEADER
-- \copy staging_skus FROM 'skus.csv' CSV HEADER

-- ========== 2. 导入 user_info / user_account ==========

INSERT INTO user_info (user_id, phone_number, name, verified)
SELECT user_id, phone_number, NULLIF(name, ''), verified = 1
FROM staging_users
ON CONFLICT (user_id) DO UPDATE SET
    phone_number = EXCLUDED.phone_number,
    name = EXCLUDED.name,
    verified = EXCLUDED.verified,
    updated_at = NOW();

INSERT INTO user_account (user_id, balance_cents)
SELECT user_id, balance_cents
FROM staging_users
ON CONFLICT (user_id) DO UPDATE SET
    balance_cents = EXCLUDED.balance_cents,
    updated_at = NOW();

-- ========== 3. 导入 device_info ==========

INSERT INTO device_info (device_id, device_name, device_type, online_status, capabilities)
SELECT
    machine_code,
    machine_name,
    'AI_CABINET_V1',
    online_status,
    jsonb_build_object('legacyMachineId', machine_id, 'vision', true)
FROM staging_devices
ON CONFLICT (device_id) DO UPDATE SET
    device_name = EXCLUDED.device_name,
    online_status = EXCLUDED.online_status,
    capabilities = EXCLUDED.capabilities,
    updated_at = NOW();

-- ========== 4. 导入 sku_catalog ==========

INSERT INTO sku_catalog (sku_id, sku_name, price_cents, weight_grams, vision_enabled)
SELECT sku_id, sku_name, price_cents, weight_grams, TRUE
FROM staging_skus
ON CONFLICT (sku_id) DO UPDATE SET
    sku_name = EXCLUDED.sku_name,
    price_cents = EXCLUDED.price_cents,
    weight_grams = EXCLUDED.weight_grams;

-- ========== 5. 校验 ==========

SELECT 'users' AS entity, COUNT(*) FROM user_info
UNION ALL SELECT 'accounts', COUNT(*) FROM user_account
UNION ALL SELECT 'devices', COUNT(*) FROM device_info
UNION ALL SELECT 'skus', COUNT(*) FROM sku_catalog;

-- 清理 staging（确认无误后）
-- DROP TABLE staging_users, staging_devices, staging_skus;
