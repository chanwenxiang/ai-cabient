-- V127: 易购式设备资产字段 + 投放生命周期 + 库存健康菜单

ALTER TABLE device_info
    ADD COLUMN IF NOT EXISTS lifecycle_status VARCHAR(32) NOT NULL DEFAULT 'DEPLOYED',
    ADD COLUMN IF NOT EXISTS imei VARCHAR(64),
    ADD COLUMN IF NOT EXISTS asset_owner VARCHAR(128),
    ADD COLUMN IF NOT EXISTS coop_mode VARCHAR(32),
    ADD COLUMN IF NOT EXISTS deposit_cents BIGINT,
    ADD COLUMN IF NOT EXISTS data_fee_cents BIGINT,
    ADD COLUMN IF NOT EXISTS ops_tags VARCHAR(512),
    ADD COLUMN IF NOT EXISTS route_code VARCHAR(64),
    ADD COLUMN IF NOT EXISTS deployed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS lifecycle_remark VARCHAR(255);

COMMENT ON COLUMN device_info.lifecycle_status IS 'INBOUND|IDLE|DEPLOYED|RETURNING|RETIRED';
COMMENT ON COLUMN device_info.coop_mode IS 'SELF|FRANCHISE|CONSIGN';

-- 已挂商户且未退役的柜默认为投放；无商户改为未投放
UPDATE device_info
SET lifecycle_status = 'IDLE'
WHERE (merchant_id IS NULL OR TRIM(merchant_id) = '')
  AND lifecycle_status = 'DEPLOYED';

UPDATE device_info
SET deployed_at = COALESCE(deployed_at, updated_at, NOW())
WHERE lifecycle_status = 'DEPLOYED' AND deployed_at IS NULL;

CREATE TABLE IF NOT EXISTS device_lifecycle_event (
    event_id    BIGSERIAL PRIMARY KEY,
    device_id   VARCHAR(64)  NOT NULL,
    from_status VARCHAR(32),
    to_status   VARCHAR(32)  NOT NULL,
    action      VARCHAR(32)  NOT NULL,
    operator_id BIGINT,
    remark      VARCHAR(255),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_device_lifecycle_event_device
    ON device_lifecycle_event (device_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_device_info_lifecycle
    ON device_info (lifecycle_status);

CREATE INDEX IF NOT EXISTS idx_device_info_route
    ON device_info (route_code);

-- 库存健康报表菜单（概览）
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 480, 400, 'ops:stock-health:list', '库存健康', 'C', '/stock-health', 60, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:stock-health:list');

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 481,
       (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:stock-health:list' LIMIT 1),
       'ops:stock-health:export', '导出库存健康', 'F', NULL, 10, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:stock-health:export');

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT DISTINCT rp.role_id, p_new.permission_id
FROM ops_permission p_gate
JOIN ops_role_permission rp ON rp.permission_id = p_gate.permission_id
JOIN ops_permission p_new ON p_new.perm_code IN ('ops:stock-health:list', 'ops:stock-health:export')
WHERE p_gate.perm_code IN ('ops:device:list', 'ops:replenishment:list', 'ops:report:device', 'ops:admin')
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code IN ('ops:stock-health:list', 'ops:stock-health:export')
ON CONFLICT DO NOTHING;

SELECT setval(
    pg_get_serial_sequence('ops_permission', 'permission_id'),
    GREATEST((SELECT COALESCE(MAX(permission_id), 1) FROM ops_permission), 1)
);
