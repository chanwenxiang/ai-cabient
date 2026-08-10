-- V170: 新页面（素材库/投放计划/客流坪效/组织与点位）登记进 RBAC 菜单树并授权内置角色

INSERT INTO ops_permission (parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 472, 'ops:ad:list', '素材库', 'C', '/ad-assets', 55, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:ad:list');

INSERT INTO ops_permission (parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 472, 'ops:ad:edit', '素材库操作', 'F', NULL, 1, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:ad:edit');
UPDATE ops_permission SET parent_id = (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:ad:list')
WHERE perm_code = 'ops:ad:edit';

INSERT INTO ops_permission (parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 472, 'ops:ad:campaign:list', '投放计划', 'C', '/ad-campaigns', 56, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:ad:campaign:list');

INSERT INTO ops_permission (parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 400, 'ops:analytics:footfall:view', '客流坪效', 'C', '/footfall', 60, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:analytics:footfall:view');

INSERT INTO ops_permission (parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 403, 'ops:org:list', '组织与点位', 'C', '/org-sites', 60, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:org:list');

INSERT INTO ops_permission (parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 403, 'ops:org:edit', '组织与点位操作', 'F', NULL, 1, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:org:edit');
UPDATE ops_permission SET parent_id = (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:org:list')
WHERE perm_code = 'ops:org:edit';

-- 授权内置角色（1=超管，2=运营）
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code IN ('ops:ad:list', 'ops:ad:edit', 'ops:ad:campaign:list',
                    'ops:analytics:footfall:view', 'ops:org:list', 'ops:org:edit')
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 2, permission_id FROM ops_permission
WHERE perm_code IN ('ops:ad:list', 'ops:ad:edit', 'ops:ad:campaign:list',
                    'ops:analytics:footfall:view', 'ops:org:list', 'ops:org:edit')
ON CONFLICT DO NOTHING;

SELECT setval(
    pg_get_serial_sequence('ops_permission', 'permission_id'),
    (SELECT MAX(permission_id) FROM ops_permission)
);
