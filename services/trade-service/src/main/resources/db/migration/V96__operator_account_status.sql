-- 运营账号状态 + 账号 CRUD 按钮权限

ALTER TABLE user_info
    ADD COLUMN IF NOT EXISTS status varchar(16) NOT NULL DEFAULT 'ACTIVE';

UPDATE user_info SET status = 'ACTIVE' WHERE status IS NULL OR status = '';

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT v.permission_id, p.permission_id, v.perm_code, v.perm_name, 'F', NULL, v.sort_order, 'ACTIVE'
FROM (VALUES
    (405, 'ops:rbac:assign:add', '新增账号', 12),
    (406, 'ops:rbac:assign:edit', '编辑账号', 13),
    (407, 'ops:rbac:assign:disable', '停用账号', 14)
) AS v(permission_id, perm_code, perm_name, sort_order)
JOIN ops_permission p ON p.perm_code = 'ops:rbac:assign'
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code IN ('ops:rbac:assign:add', 'ops:rbac:assign:edit', 'ops:rbac:assign:disable')
ON CONFLICT DO NOTHING;

SELECT setval(
    pg_get_serial_sequence('ops_permission', 'permission_id'),
    GREATEST((SELECT COALESCE(MAX(permission_id), 1) FROM ops_permission), 1)
);
