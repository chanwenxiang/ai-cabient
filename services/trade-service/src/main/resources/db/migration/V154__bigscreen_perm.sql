-- 运营大屏页面权限：挂在「概览」导航(ops:nav:overview=400)下，路径与前端路由一致
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 576, 400, 'ops:bigscreen:view', '运营大屏', 'C', '/big-screen', 60, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:bigscreen:view');

-- 角色授权：拥有运营工作台权限的角色可看；admin(role 1) 全量
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT DISTINCT rp.role_id, p_new.permission_id
FROM ops_permission p_gate
JOIN ops_role_permission rp ON rp.permission_id = p_gate.permission_id
JOIN ops_permission p_new ON p_new.perm_code = 'ops:bigscreen:view'
WHERE p_gate.perm_code = 'ops:dashboard:view'
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code = 'ops:bigscreen:view'
ON CONFLICT DO NOTHING;
