-- 活动上架与停用拆分
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order)
SELECT 445,
       (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:promotion:list' LIMIT 1),
       'ops:promotion:launch',
       '上架活动',
       'F',
       NULL,
       3
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:promotion:launch');

-- 曾有停用权的角色自动获得上架权
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT rp.role_id, p_launch.permission_id
FROM ops_permission p_stop
JOIN ops_role_permission rp ON rp.permission_id = p_stop.permission_id
JOIN ops_permission p_launch ON p_launch.perm_code = 'ops:promotion:launch'
WHERE p_stop.perm_code = 'ops:promotion:stop'
ON CONFLICT DO NOTHING;

-- 有创建/导入权的角色也能上架（导入后常自动启用）
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT DISTINCT rp.role_id, p_launch.permission_id
FROM ops_permission p_src
JOIN ops_role_permission rp ON rp.permission_id = p_src.permission_id
JOIN ops_permission p_launch ON p_launch.perm_code = 'ops:promotion:launch'
WHERE p_src.perm_code IN ('ops:promotion:create', 'ops:promotion:import')
ON CONFLICT DO NOTHING;

SELECT setval(
    pg_get_serial_sequence('ops_permission', 'permission_id'),
    GREATEST((SELECT COALESCE(MAX(permission_id), 1) FROM ops_permission), 1)
);
