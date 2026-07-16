-- 若依系统管理：拆分运营账号 / 角色 / 菜单为独立 C 级菜单
UPDATE ops_permission
SET path = '/admin/roles', perm_name = '角色管理'
WHERE perm_code = 'ops:rbac:role';

UPDATE ops_permission
SET perm_type = 'C',
    path = '/admin/operators',
    perm_name = '运营账号',
    sort_order = 0
WHERE perm_code = 'ops:rbac:assign';

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order)
VALUES (133, 130, 'ops:rbac:menu', '菜单管理', 'C', '/admin/menus', 2)
ON CONFLICT (perm_code) DO NOTHING;

UPDATE ops_permission
SET perm_name = '系统权限'
WHERE perm_code = 'ops:rbac';

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission WHERE perm_code = 'ops:rbac:menu'
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 2, permission_id FROM ops_permission
WHERE perm_code IN ('ops:rbac:menu', 'ops:rbac:assign')
ON CONFLICT DO NOTHING;
