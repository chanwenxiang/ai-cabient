-- 用户实名核验按钮权限（与列表只读分离）
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order)
VALUES (444, 61, 'ops:user:verify', '用户实名核验', 'F', NULL, 10)
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission WHERE perm_code = 'ops:user:verify'
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 2, permission_id FROM ops_permission WHERE perm_code = 'ops:user:verify'
ON CONFLICT DO NOTHING;

SELECT setval(
    pg_get_serial_sequence('ops_permission', 'permission_id'),
    GREATEST((SELECT COALESCE(MAX(permission_id), 1) FROM ops_permission), 1)
);
