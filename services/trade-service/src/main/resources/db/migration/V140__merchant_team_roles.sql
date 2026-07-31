-- V140: 商户团队角色模板 + 成员生命周期权限（改角色/停用/重置密码）

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 502, 215, 'merchant:users:edit', '编辑成员', 'F', NULL, 20, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'merchant:users:edit');

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 503, 215, 'merchant:users:disable', '停用成员', 'F', NULL, 30, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'merchant:users:disable');

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 504, 215, 'merchant:users:reset-password', '重置密码', 'F', NULL, 40, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'merchant:users:reset-password');

-- 仅商户管理员可管理成员生命周期
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 6, permission_id FROM ops_permission
WHERE perm_code IN ('merchant:users:edit', 'merchant:users:disable', 'merchant:users:reset-password')
ON CONFLICT DO NOTHING;

-- 店长：现场 + 经营只读 + 看团队，不可邀请/停用
INSERT INTO ops_role (role_id, role_key, role_name, remark) VALUES
    (10, 'merchant_store_manager', '商户店长', '现场与经营只读，可查看团队')
ON CONFLICT (role_key) DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 10, permission_id FROM ops_permission WHERE perm_code IN (
    'merchant:portal:access',
    'merchant:nav:field',
    'merchant:nav:biz',
    'merchant:nav:team',
    'merchant:devices:list',
    'merchant:devices:detail',
    'merchant:slots:view',
    'merchant:inventory:view',
    'merchant:alerts:view',
    'merchant:temp:history',
    'merchant:replenishment:view',
    'merchant:replenishment:request',
    'merchant:orders:list',
    'merchant:disputes:list',
    'merchant:disputes:reply',
    'merchant:pricing:view',
    'merchant:splits:list',
    'merchant:settlements:view',
    'merchant:reports:view',
    'merchant:analytics:view',
    'merchant:trend:view',
    'merchant:users:list'
)
ON CONFLICT DO NOTHING;

-- 补货员：现场作业为主
INSERT INTO ops_role (role_id, role_key, role_name, remark) VALUES
    (11, 'merchant_replenisher', '商户补货员', '柜机/补货/库存现场作业')
ON CONFLICT (role_key) DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 11, permission_id FROM ops_permission WHERE perm_code IN (
    'merchant:portal:access',
    'merchant:nav:field',
    'merchant:devices:list',
    'merchant:devices:detail',
    'merchant:slots:view',
    'merchant:inventory:view',
    'merchant:alerts:view',
    'merchant:temp:history',
    'merchant:replenishment:view',
    'merchant:replenishment:request'
)
ON CONFLICT DO NOTHING;

-- 财务补钱包只读（提现申请仍仅管理员）
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 8, permission_id FROM ops_permission
WHERE perm_code IN ('merchant:wallet:view', 'merchant:nav:biz')
ON CONFLICT DO NOTHING;

-- 店员补全若干只读能力（与现网对齐，不含邀请/提现）
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 7, permission_id FROM ops_permission
WHERE perm_code IN (
    'merchant:replenishment:request',
    'merchant:disputes:reply'
)
ON CONFLICT DO NOTHING;

-- 角色模板表补充店长/财务/补货员映射提示
INSERT INTO merchant_role_template (template_key, template_name, description, permission_hint, sort_order)
VALUES
    ('STORE_MANAGER', '店长', '推荐：现场+经营只读，可看团队', 'merchant_store_manager', 15),
    ('FINANCE', '财务', '推荐：结算对账与钱包只读', 'merchant_finance', 25),
    ('REPLENISHER', '补货员', '推荐：柜机补货与库存', 'merchant_replenisher', 35)
ON CONFLICT (template_key) DO UPDATE SET
    template_name = EXCLUDED.template_name,
    description = EXCLUDED.description,
    permission_hint = EXCLUDED.permission_hint,
    sort_order = EXCLUDED.sort_order;

-- 演示店长 / 补货员账号（密码同演示 123456）
INSERT INTO user_info (user_id, phone_number, name, verified, status)
VALUES
    (100000006, '13800138006', '演示店长', TRUE, 'ACTIVE'),
    (100000007, '13800138007', '演示补货员', TRUE, 'ACTIVE')
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO user_account (user_id, balance_cents)
VALUES (100000006, 0), (100000007, 0)
ON CONFLICT (user_id) DO NOTHING;

UPDATE user_info
SET password_hash = '$2b$10$wtX2jX5K5h0IuUQxT2BAqOMT19ngpMGUDR76A3MdQcGnoCZzM6oFC'
WHERE user_id IN (100000006, 100000007)
  AND (password_hash IS NULL OR password_hash = '');

INSERT INTO ops_user_role (user_id, role_id) VALUES (100000006, 10)
ON CONFLICT DO NOTHING;
INSERT INTO ops_user_role (user_id, role_id) VALUES (100000007, 11)
ON CONFLICT DO NOTHING;
INSERT INTO ops_user_merchant (user_id, merchant_id) VALUES (100000006, 'MCH-DEFAULT')
ON CONFLICT DO NOTHING;
INSERT INTO ops_user_merchant (user_id, merchant_id) VALUES (100000007, 'MCH-DEFAULT')
ON CONFLICT DO NOTHING;

SELECT setval(
    pg_get_serial_sequence('ops_permission', 'permission_id'),
    GREATEST((SELECT COALESCE(MAX(permission_id), 1) FROM ops_permission), 1)
);
