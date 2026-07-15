-- 操作审计独立权限（借鉴 RuoYi：操作日志 + 最近操作）

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order) VALUES
    (140, 1, 'ops:audit',           '操作审计', 'M', NULL,             14),
    (141, 140, 'ops:audit:list',    '操作日志', 'C', '/admin/audit',   1),
    (142, 140, 'ops:audit:recent',  '最近操作', 'C', '/admin/recent',  2)
ON CONFLICT (perm_code) DO NOTHING;

-- 超级管理员
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission WHERE perm_code IN ('ops:audit', 'ops:audit:list', 'ops:audit:recent')
ON CONFLICT DO NOTHING;

-- 运营人员
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 2, permission_id FROM ops_permission WHERE perm_code IN ('ops:audit', 'ops:audit:list', 'ops:audit:recent')
ON CONFLICT DO NOTHING;

-- 只读：可查看日志
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 5, permission_id FROM ops_permission WHERE perm_code IN ('ops:audit', 'ops:audit:list', 'ops:audit:recent')
ON CONFLICT DO NOTHING;
