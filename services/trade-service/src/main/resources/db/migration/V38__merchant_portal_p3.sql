-- 商户门户 P3：温度历史、店员角色、补货明细权限

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order) VALUES
    (217, 200, 'merchant:temp:history', '温度历史', 'C', '/merchant/devices', 17)
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO ops_role (role_id, role_key, role_name, remark) VALUES
    (7, 'merchant_staff', '商户店员', '商户门户只读，不可改设置/邀请成员')
ON CONFLICT (role_key) DO NOTHING;

-- 店员角色：只读类权限
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 7, permission_id FROM ops_permission WHERE perm_code IN (
    'merchant:portal:access', 'merchant:devices:list', 'merchant:devices:detail',
    'merchant:orders:list', 'merchant:alerts:view', 'merchant:trend:view',
    'merchant:splits:list', 'merchant:inventory:view', 'merchant:disputes:list',
    'merchant:reports:view', 'merchant:reports:export', 'merchant:replenishment:view',
    'merchant:users:list', 'merchant:temp:history'
)
ON CONFLICT DO NOTHING;

-- 管理员角色补温度历史
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 6, permission_id FROM ops_permission WHERE perm_code = 'merchant:temp:history'
ON CONFLICT DO NOTHING;

-- 演示店员改用 merchant_staff 角色
DELETE FROM ops_user_role WHERE user_id = 100000003 AND role_id = 6;
INSERT INTO ops_user_role (user_id, role_id) VALUES (100000003, 7)
ON CONFLICT DO NOTHING;

-- 温度历史（心跳采样）
CREATE TABLE IF NOT EXISTS device_temperature_reading (
    reading_id   BIGSERIAL PRIMARY KEY,
    device_id    VARCHAR(64)  NOT NULL REFERENCES device_info(device_id),
    temp_c       INT          NOT NULL,
    reported_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_device_temp_reading_device_time
    ON device_temperature_reading (device_id, reported_at DESC);
