-- 商户门户 P2：导出/补货/子账号/温度遥测

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order) VALUES
    (213, 200, 'merchant:reports:export',       '报表导出',     'C', '/merchant/reports',       13),
    (214, 200, 'merchant:replenishment:view',   '补货进度',     'C', '/merchant/replenishment', 14),
    (215, 200, 'merchant:users:list',           '团队成员',     'C', '/merchant/team',          15),
    (216, 200, 'merchant:users:invite',         '添加成员',     'C', '/merchant/team',          16)
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 6, permission_id FROM ops_permission WHERE perm_code IN (
    'merchant:reports:export', 'merchant:replenishment:view',
    'merchant:users:list', 'merchant:users:invite'
)
ON CONFLICT DO NOTHING;

-- 设备实时温度（心跳上报）
ALTER TABLE device_info ADD COLUMN IF NOT EXISTS current_temp_c INT;
ALTER TABLE device_info ADD COLUMN IF NOT EXISTS temp_reported_at TIMESTAMPTZ;

-- 演示：商户子账号 13800138002 / 123456，同绑定 MCH-DEFAULT
INSERT INTO user_info (user_id, phone_number, name, verified)
VALUES (100000003, '13800138002', '商户店员', TRUE)
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO user_account (user_id, balance_cents)
VALUES (100000003, 0)
ON CONFLICT (user_id) DO NOTHING;

UPDATE user_info
SET password_hash = '$2b$10$wtX2jX5K5h0IuUQxT2BAqOMT19ngpMGUDR76A3MdQcGnoCZzM6oFC'
WHERE user_id = 100000003
  AND (password_hash IS NULL OR password_hash = '');

INSERT INTO ops_user_role (user_id, role_id) VALUES (100000003, 6)
ON CONFLICT DO NOTHING;

INSERT INTO ops_user_merchant (user_id, merchant_id) VALUES (100000003, 'MCH-DEFAULT')
ON CONFLICT DO NOTHING;
