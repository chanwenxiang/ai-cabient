-- DevOps 中心仅超管可进：收回 V229/V234 按 ops:config:list 扩权后残留在
-- operator / viewer / replenisher / 商户角色等上的 devops 权限。
-- 财务侧已在 V248 收回；本迁移对所有非 admin 角色统一清理。

DELETE FROM ops_role_permission rp
USING ops_role r, ops_permission p
WHERE rp.role_id = r.role_id
  AND rp.permission_id = p.permission_id
  AND r.role_key <> 'admin'
  AND p.perm_code IN (
    'ops:devops:view',
    'ops:devops:scan'
  );

-- 确保超管仍持有（幂等）
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM ops_role r
CROSS JOIN ops_permission p
WHERE r.role_key = 'admin'
  AND p.perm_code IN ('ops:devops:view', 'ops:devops:scan')
  AND NOT EXISTS (
    SELECT 1
    FROM ops_role_permission rp
    WHERE rp.role_id = r.role_id
      AND rp.permission_id = p.permission_id
  );
