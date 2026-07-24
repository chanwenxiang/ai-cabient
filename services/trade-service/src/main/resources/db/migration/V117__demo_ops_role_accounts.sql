-- 多角色演示账号（密码均为 123456，hash 与 V20/V116 一致）
-- admin 13900000001 / finance 13900000002 已有；补齐 operator / replenisher / viewer

-- 1) 运营人员演示：设备/订单/会话/争议/SLA
INSERT INTO user_info (user_id, phone_number, name, verified, password_hash)
VALUES (
    100000008,
    '13900000003',
    '运营演示',
    TRUE,
    '$2b$10$wtX2jX5K5h0IuUQxT2BAqOMT19ngpMGUDR76A3MdQcGnoCZzM6oFC'
)
ON CONFLICT (user_id) DO UPDATE
SET phone_number = EXCLUDED.phone_number,
    name = EXCLUDED.name,
    verified = TRUE,
    password_hash = COALESCE(user_info.password_hash, EXCLUDED.password_hash);

INSERT INTO user_account (user_id, balance_cents)
VALUES (100000008, 0)
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO ops_user_role (user_id, role_id)
SELECT 100000008, role_id FROM ops_role WHERE role_key = 'operator'
ON CONFLICT DO NOTHING;

-- 2) 补货员演示：仅补货相关
INSERT INTO user_info (user_id, phone_number, name, verified, password_hash)
VALUES (
    100000009,
    '13900000004',
    '补货演示',
    TRUE,
    '$2b$10$wtX2jX5K5h0IuUQxT2BAqOMT19ngpMGUDR76A3MdQcGnoCZzM6oFC'
)
ON CONFLICT (user_id) DO UPDATE
SET phone_number = EXCLUDED.phone_number,
    name = EXCLUDED.name,
    verified = TRUE,
    password_hash = COALESCE(user_info.password_hash, EXCLUDED.password_hash);

INSERT INTO user_account (user_id, balance_cents)
VALUES (100000009, 0)
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO ops_user_role (user_id, role_id)
SELECT 100000009, role_id FROM ops_role WHERE role_key = 'replenisher'
ON CONFLICT DO NOTHING;

-- 3) 只读演示：列表查看，无编辑类 F 权限
INSERT INTO user_info (user_id, phone_number, name, verified, password_hash)
VALUES (
    100000010,
    '13900000005',
    '只读演示',
    TRUE,
    '$2b$10$wtX2jX5K5h0IuUQxT2BAqOMT19ngpMGUDR76A3MdQcGnoCZzM6oFC'
)
ON CONFLICT (user_id) DO UPDATE
SET phone_number = EXCLUDED.phone_number,
    name = EXCLUDED.name,
    verified = TRUE,
    password_hash = COALESCE(user_info.password_hash, EXCLUDED.password_hash);

INSERT INTO user_account (user_id, balance_cents)
VALUES (100000010, 0)
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO ops_user_role (user_id, role_id)
SELECT 100000010, role_id FROM ops_role WHERE role_key = 'viewer'
ON CONFLICT DO NOTHING;

-- 4) 运营人员补齐异常中心菜单（日运营 triage）
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM ops_role r
CROSS JOIN ops_permission p
WHERE r.role_key = 'operator'
  AND p.perm_code IN ('ops:exception:list', 'ops:exception:handle', 'ops:exception:export', 'ops:nav:biz', 'ops:nav:overview')
ON CONFLICT DO NOTHING;

-- 5) 补货员可见运营分组目录 + 仓库只读（便于履约仓储树展开）
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM ops_role r
CROSS JOIN ops_permission p
WHERE r.role_key = 'replenisher'
  AND p.perm_code IN ('ops:nav:ops', 'ops:warehouse:list')
ON CONFLICT DO NOTHING;

-- 6) 财务 / 只读补齐侧栏分组目录（菜单树展示与权限裁剪一致）
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM ops_role r
CROSS JOIN ops_permission p
WHERE r.role_key = 'finance'
  AND p.perm_code IN ('ops:nav:overview', 'ops:nav:ops')
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM ops_role r
CROSS JOIN ops_permission p
WHERE r.role_key = 'viewer'
  AND p.perm_code IN ('ops:nav:overview', 'ops:nav:biz', 'ops:nav:ops')
ON CONFLICT DO NOTHING;
