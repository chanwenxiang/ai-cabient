-- 视觉映射与上传队列运营权限

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order) VALUES
    (150, 1, 'ops:vision',              '视觉映射',     'M', NULL,                    15),
    (151, 150, 'ops:vision:list',      '映射列表',     'C', '/admin/vision-mappings', 1),
    (152, 150, 'ops:vision:edit',      '映射编辑',     'F', NULL,                     2),
    (160, 30, 'ops:session:upload',    '上传队列',     'C', '/admin/upload-queue',    3)
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code IN ('ops:vision', 'ops:vision:list', 'ops:vision:edit', 'ops:session:upload')
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 2, permission_id FROM ops_permission
WHERE perm_code IN ('ops:vision', 'ops:vision:list', 'ops:vision:edit', 'ops:session:upload')
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 5, permission_id FROM ops_permission
WHERE perm_code IN ('ops:vision', 'ops:vision:list', 'ops:session:upload')
ON CONFLICT DO NOTHING;
