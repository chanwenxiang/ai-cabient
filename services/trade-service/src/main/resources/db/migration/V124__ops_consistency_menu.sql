-- V124: 运营侧「数据一致性」菜单与按钮权限（对齐 admin-vue /consistency）

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 454, 402, 'ops:consistency:list', '数据一致性', 'C', '/admin/consistency', 35, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:consistency:list');

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 455,
       (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:consistency:list' LIMIT 1),
       'ops:consistency:run',
       '执行巡检',
       'F',
       NULL,
       10,
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:consistency:run');

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 456,
       (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:consistency:list' LIMIT 1),
       'ops:consistency:fix',
       '修复不一致',
       'F',
       NULL,
       20,
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:consistency:fix');

-- 有财务查看或订单列表权的角色自动获得一致性菜单与操作
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT DISTINCT rp.role_id, p_new.permission_id
FROM ops_permission p_gate
JOIN ops_role_permission rp ON rp.permission_id = p_gate.permission_id
JOIN ops_permission p_new ON p_new.perm_code IN (
    'ops:consistency:list', 'ops:consistency:run', 'ops:consistency:fix'
)
WHERE p_gate.perm_code IN ('ops:finance:view', 'ops:order:list')
ON CONFLICT DO NOTHING;

-- 超管兜底
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code IN ('ops:consistency:list', 'ops:consistency:run', 'ops:consistency:fix')
ON CONFLICT DO NOTHING;

SELECT setval(
    pg_get_serial_sequence('ops_permission', 'permission_id'),
    GREATEST((SELECT COALESCE(MAX(permission_id), 1) FROM ops_permission), 1)
);
