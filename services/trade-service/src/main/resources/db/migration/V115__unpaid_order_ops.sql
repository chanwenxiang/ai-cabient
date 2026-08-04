-- 待支付催付 / 关单按钮权限（避免与 V111 采购权限 ID 冲突）
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order)
SELECT 449,
       (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:order:list' LIMIT 1),
       'ops:order:remind',
       '催付待支付',
       'F',
       NULL,
       20
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:order:remind');

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order)
SELECT 450,
       (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:order:list' LIMIT 1),
       'ops:order:cancel',
       '关闭待支付',
       'F',
       NULL,
       30
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:order:cancel');

-- 有订单列表权的角色自动获得催付/关单
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT rp.role_id, p_new.permission_id
FROM ops_permission p_list
JOIN ops_role_permission rp ON rp.permission_id = p_list.permission_id
JOIN ops_permission p_new ON p_new.perm_code IN ('ops:order:remind', 'ops:order:cancel')
WHERE p_list.perm_code = 'ops:order:list'
ON CONFLICT DO NOTHING;

SELECT setval(
    pg_get_serial_sequence('ops_permission', 'permission_id'),
    GREATEST((SELECT COALESCE(MAX(permission_id), 1) FROM ops_permission), 1)
);
