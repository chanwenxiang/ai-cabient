-- 收紧 viewer：去掉后续迁移按 device:list / order:list 扩散挂上的写操作权限
-- 对齐 V118「只读」意图；保留可读菜单（list / view）

DELETE FROM ops_role_permission rp
USING ops_role r, ops_permission p
WHERE rp.role_id = r.role_id
  AND rp.permission_id = p.permission_id
  AND r.role_key = 'viewer'
  AND p.perm_code IN (
    'ops:repair:edit',
    'ops:consistency:run',
    'ops:consistency:fix',
    'ops:rbac:assign:device'
  );

-- 只读可看一致性列表与维修工单列表（若尚无则补挂）
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM ops_role r
CROSS JOIN ops_permission p
WHERE r.role_key = 'viewer'
  AND p.perm_code IN ('ops:consistency:list', 'ops:repair:list')
  AND p.status = 'ACTIVE'
ON CONFLICT DO NOTHING;
