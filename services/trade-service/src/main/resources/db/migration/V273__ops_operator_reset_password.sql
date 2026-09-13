-- 运营账号：独立「重置密码」按钮权限（不可自助改他人密码走编辑态）

INSERT INTO ops_permission (parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT p.permission_id, 'ops:rbac:assign:reset-password', '重置密码', 'F', NULL, 15, 'ACTIVE'
FROM ops_permission p
WHERE p.perm_code = 'ops:rbac:assign'
  AND NOT EXISTS (
      SELECT 1 FROM ops_permission x WHERE x.perm_code = 'ops:rbac:assign:reset-password'
  );

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code = 'ops:rbac:assign:reset-password'
ON CONFLICT DO NOTHING;

SELECT setval(
    pg_get_serial_sequence('ops_permission', 'permission_id'),
    GREATEST((SELECT COALESCE(MAX(permission_id), 1) FROM ops_permission), 1)
);
