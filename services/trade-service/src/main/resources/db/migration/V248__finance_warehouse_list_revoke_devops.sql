-- 财务角色权限纠偏：
-- 1) 应能进仓库并看到采购 Tab（审批第二节点）
-- 2) 不应看见「系统」下的 DevOps（V229 按 config 扩权误挂后残留）

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM ops_role r
CROSS JOIN ops_permission p
WHERE r.role_key = 'finance'
  AND p.perm_code IN (
    'ops:warehouse:list',
    'ops:procurement:list'
  )
ON CONFLICT DO NOTHING;

DELETE FROM ops_role_permission rp
USING ops_role r, ops_permission p
WHERE rp.role_id = r.role_id
  AND rp.permission_id = p.permission_id
  AND r.role_key = 'finance'
  AND p.perm_code IN (
    'ops:devops:view',
    'ops:devops:scan'
  );
