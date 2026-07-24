-- 财务演示账号 + 恢复分账按钮权限（V93 误把 ops:merchant:split 当孤儿菜单停用）
-- 分账仍在「商户与分账」页，改为挂在 ops:merchant:list 下的 F 码

-- 1) 分账操作：重新启用，类型改为按钮权限
UPDATE ops_permission
SET status = 'ACTIVE',
    perm_type = 'F',
    parent_id = 171,
    path = NULL,
    perm_name = '分账明细',
    sort_order = 20
WHERE perm_code = 'ops:merchant:split';

-- 2) 财务角色补齐导出与分账权限
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM ops_role r
CROSS JOIN ops_permission p
WHERE r.role_key = 'finance'
  AND p.perm_code IN (
      'ops:finance:export',
      'ops:merchant:export',
      'ops:reconciliation:export',
      'ops:merchant:split'
  )
ON CONFLICT DO NOTHING;

-- 3) 演示财务账号：13900000002 / 123456（userId 100000007）
INSERT INTO user_info (user_id, phone_number, name, verified, password_hash)
VALUES (
    100000007,
    '13900000002',
    '财务演示',
    TRUE,
    '$2b$10$wtX2jX5K5h0IuUQxT2BAqOMT19ngpMGUDR76A3MdQcGnoCZzM6oFC'
)
ON CONFLICT (user_id) DO UPDATE
SET phone_number = EXCLUDED.phone_number,
    name = EXCLUDED.name,
    verified = TRUE,
    password_hash = COALESCE(user_info.password_hash, EXCLUDED.password_hash);

INSERT INTO user_account (user_id, balance_cents)
VALUES (100000007, 0)
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO ops_user_role (user_id, role_id)
SELECT 100000007, role_id FROM ops_role WHERE role_key = 'finance'
ON CONFLICT DO NOTHING;
