-- 采购/供应商独立权限（与补货编辑解耦）
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order)
SELECT 447,
       (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:warehouse:list' LIMIT 1),
       'ops:procurement:list',
       '采购查看',
       'F',
       NULL,
       30
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:procurement:list');

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order)
SELECT 448,
       (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:warehouse:list' LIMIT 1),
       'ops:procurement:edit',
       '采购作业',
       'F',
       NULL,
       31
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:procurement:edit');

-- 曾有仓库列表权 → 采购查看
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT rp.role_id, p_new.permission_id
FROM ops_permission p_src
JOIN ops_role_permission rp ON rp.permission_id = p_src.permission_id
JOIN ops_permission p_new ON p_new.perm_code = 'ops:procurement:list'
WHERE p_src.perm_code = 'ops:warehouse:list'
ON CONFLICT DO NOTHING;

-- 曾有仓库作业权 → 采购作业
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT rp.role_id, p_new.permission_id
FROM ops_permission p_src
JOIN ops_role_permission rp ON rp.permission_id = p_src.permission_id
JOIN ops_permission p_new ON p_new.perm_code = 'ops:procurement:edit'
WHERE p_src.perm_code = 'ops:warehouse:edit'
ON CONFLICT DO NOTHING;

SELECT setval(
    pg_get_serial_sequence('ops_permission', 'permission_id'),
    GREATEST((SELECT COALESCE(MAX(permission_id), 1) FROM ops_permission), 1)
);
