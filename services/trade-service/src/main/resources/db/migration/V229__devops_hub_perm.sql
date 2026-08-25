-- DevOps 中心：运营后台集成 Grafana / Prometheus / Jenkins / SonarQube / GitHub 入口
-- 挂在「系统」导航 ops:nav:sys = 403 下
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 580, 403, 'ops:devops:view', 'DevOps 中心', 'C', '/devops', 95, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:devops:view');

-- 拥有参数配置权限的角色可访问 DevOps 中心
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT DISTINCT rp.role_id, p_new.permission_id
FROM ops_permission p_gate
JOIN ops_role_permission rp ON rp.permission_id = p_gate.permission_id
JOIN ops_permission p_new ON p_new.perm_code = 'ops:devops:view'
WHERE p_gate.perm_code = 'ops:config:list'
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code = 'ops:devops:view'
ON CONFLICT DO NOTHING;
