-- 商户自助门户：角色、权限与演示账号

INSERT INTO ops_role (role_id, role_key, role_name, remark) VALUES
    (6, 'merchant', '商户管理员', '商户自助门户，仅可见绑定商户数据')
ON CONFLICT (role_key) DO NOTHING;

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order) VALUES
    (200, 0,   'merchant',                 '商户门户',     'M', NULL,               20),
    (201, 200, 'merchant:portal:access',   '门户访问',     'C', '/merchant',        1),
    (202, 200, 'merchant:devices:list',    '我的柜机',     'C', '/merchant/devices', 2),
    (203, 200, 'merchant:splits:list',     '分账明细',     'C', '/merchant/splits',  3)
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 6, permission_id FROM ops_permission WHERE perm_code LIKE 'merchant:%'
ON CONFLICT DO NOTHING;

-- 商户演示账号：13800138001 / 123456，绑定默认商户 MCH-DEFAULT
INSERT INTO user_info (user_id, phone_number, name, verified)
VALUES (100000002, '13800138001', '商户测试', TRUE)
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO user_account (user_id, balance_cents)
VALUES (100000002, 0)
ON CONFLICT (user_id) DO NOTHING;

UPDATE user_info
SET password_hash = '$2b$10$wtX2jX5K5h0IuUQxT2BAqOMT19ngpMGUDR76A3MdQcGnoCZzM6oFC'
WHERE user_id = 100000002
  AND (password_hash IS NULL OR password_hash = '');

INSERT INTO ops_user_role (user_id, role_id) VALUES (100000002, 6)
ON CONFLICT DO NOTHING;

INSERT INTO ops_user_merchant (user_id, merchant_id) VALUES (100000002, 'MCH-DEFAULT')
ON CONFLICT DO NOTHING;
