-- 履约角色（补货员等）设备基础信息只读权限：
-- 用于补货/仓库页面的设备下拉与名称展示；不授予完整设备管理（菜单仍按 ops:device:list 控制）
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 570,
       (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:device:list' AND perm_type = 'C' LIMIT 1),
       'ops:device:ref', '设备基础信息只读', 'F', NULL, 10, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:device:ref');

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM ops_role r
CROSS JOIN ops_permission p
WHERE r.role_key = 'replenisher'
  AND p.perm_code = 'ops:device:ref'
ON CONFLICT DO NOTHING;
