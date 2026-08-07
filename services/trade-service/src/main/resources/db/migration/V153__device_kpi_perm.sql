-- 设备可用性 KPI 页面权限：挂在「设备商品」导航(ops:nav:device=470)下，路径与前端路由一致
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 575, 470, 'ops:device-kpi:view', '设备可用性', 'C', '/device-kpi', 60, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:device-kpi:view');

-- 角色授权：拥有设备列表或数据分析权限的角色可查看；admin(role 1) 全量
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT DISTINCT rp.role_id, p_new.permission_id
FROM ops_permission p_gate
JOIN ops_role_permission rp ON rp.permission_id = p_gate.permission_id
JOIN ops_permission p_new ON p_new.perm_code = 'ops:device-kpi:view'
WHERE p_gate.perm_code IN ('ops:device:list', 'ops:analytics:view')
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code = 'ops:device-kpi:view'
ON CONFLICT DO NOTHING;
