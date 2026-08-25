-- DevOps：触发 Sonar 重扫（按钮）权限
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 581, 580, 'ops:devops:scan', '触发 Sonar 扫描', 'F', NULL, 10, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:devops:scan');

-- 能进 DevOps 中心的角色同步获得扫描权限
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT DISTINCT rp.role_id, p_new.permission_id
FROM ops_permission p_gate
JOIN ops_role_permission rp ON rp.permission_id = p_gate.permission_id
JOIN ops_permission p_new ON p_new.perm_code = 'ops:devops:scan'
WHERE p_gate.perm_code = 'ops:devops:view'
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code = 'ops:devops:scan'
ON CONFLICT DO NOTHING;
