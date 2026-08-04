-- 设备新建、参数删除按钮权限；优惠券 edit 文案对齐真实能力
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order)
SELECT 560, permission_id, 'ops:device:create', '新建设备', 'F', NULL, 3
FROM ops_permission WHERE perm_code = 'ops:device:list'
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order)
SELECT 561, permission_id, 'ops:config:delete', '删除参数', 'F', NULL, 3
FROM ops_permission WHERE perm_code = 'ops:config:list'
ON CONFLICT (perm_code) DO NOTHING;

UPDATE ops_permission SET perm_name = '编辑优惠券' WHERE perm_code = 'ops:coupon:edit';

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT rp.role_id, p_new.permission_id
FROM ops_permission p_edit
JOIN ops_role_permission rp ON rp.permission_id = p_edit.permission_id
JOIN ops_permission p_new ON p_new.perm_code = 'ops:device:create'
WHERE p_edit.perm_code = 'ops:device:edit'
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT rp.role_id, p_new.permission_id
FROM ops_permission p_edit
JOIN ops_role_permission rp ON rp.permission_id = p_edit.permission_id
JOIN ops_permission p_new ON p_new.perm_code = 'ops:config:delete'
WHERE p_edit.perm_code = 'ops:config:edit'
ON CONFLICT DO NOTHING;
