-- 收紧演示角色权限边界，便于多角色菜单/按钮差异化验收
-- 历史迁移（V90/V92/V106 等）给 operator 挂了 RBAC/仓库/财务导出等，导致与超管差异不明显

-- 1) 清空非 admin 角色的权限，再按职责回填
DELETE FROM ops_role_permission rp
USING ops_role r
WHERE rp.role_id = r.role_id
  AND r.role_key IN ('operator', 'replenisher', 'finance', 'viewer');

-- 2) 运营人员：日运营 triage（设备/交易/争议/异常），无系统 RBAC、无财务分账
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM ops_role r
CROSS JOIN ops_permission p
WHERE r.role_key = 'operator'
  AND p.status = 'ACTIVE'
  AND (
    p.perm_code IN (
      'ops', 'ops:nav:overview', 'ops:nav:biz', 'ops:nav:ops',
      'ops:dashboard:view',
      'ops:device:list', 'ops:device:edit', 'ops:device:export',
      'ops:session:list', 'ops:session:cancel', 'ops:session:upload', 'ops:session:export',
      'ops:order:list', 'ops:order:export',
      'ops:sku:list', 'ops:sku:edit', 'ops:sku:export',
      'ops:vision:list', 'ops:vision:export',
      'ops:dispute', 'ops:dispute:export',
      'ops:exception:list', 'ops:exception:handle', 'ops:exception:export',
      'ops:sla',
      'ops:feedback', 'ops:feedback:reply', 'ops:feedback:export',
      'ops:ota:list',
      'ops:user:list'
    )
    OR p.perm_code LIKE 'ops:dispute:%'
  )
ON CONFLICT DO NOTHING;

-- 3) 补货员：履约仓储
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM ops_role r
CROSS JOIN ops_permission p
WHERE r.role_key = 'replenisher'
  AND p.status = 'ACTIVE'
  AND (
    p.perm_code IN ('ops', 'ops:nav:ops', 'ops:warehouse:list')
    OR p.perm_code LIKE 'ops:replenishment%'
  )
ON CONFLICT DO NOTHING;

-- 4) 财务：概览财务 + 商户对账
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM ops_role r
CROSS JOIN ops_permission p
WHERE r.role_key = 'finance'
  AND p.status = 'ACTIVE'
  AND p.perm_code IN (
    'ops', 'ops:nav:overview', 'ops:nav:ops',
    'ops:dashboard:view',
    'ops:finance:view', 'ops:finance:export',
    'ops:merchant:list', 'ops:merchant:export', 'ops:merchant:split',
    'ops:reconciliation:list', 'ops:reconciliation:run', 'ops:reconciliation:export'
  )
ON CONFLICT DO NOTHING;

-- 5) 只读：列表可见，无编辑/处理类 F 码
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM ops_role r
CROSS JOIN ops_permission p
WHERE r.role_key = 'viewer'
  AND p.status = 'ACTIVE'
  AND p.perm_code IN (
    'ops', 'ops:nav:overview', 'ops:nav:biz', 'ops:nav:ops',
    'ops:dashboard:view',
    'ops:device:list',
    'ops:session:list',
    'ops:order:list',
    'ops:sku:list',
    'ops:user:list',
    'ops:sla',
    'ops:dispute',
    'ops:exception:list'
  )
ON CONFLICT DO NOTHING;
