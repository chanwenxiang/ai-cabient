-- 平台补货员与商户补货员共用补货小程序：用商户归属区分可见任务范围
-- replenisher：保留后台调度/仓库，并授予与 merchant_replenisher 相同的现场 merchant:* 权限

UPDATE ops_role
SET role_name = '平台补货员',
    remark = '后台：补货调度/仓库；小程序：现场签到开门上架（须绑定商户范围，与商户补货员同端）'
WHERE role_key = 'replenisher';

UPDATE ops_role
SET role_name = '商户补货员',
    remark = '商户端小程序现场补货；须绑定所属商户；与平台补货员同端，靠商户归属区分任务'
WHERE role_key = 'merchant_replenisher';

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM ops_role r
CROSS JOIN ops_permission p
WHERE r.role_key = 'replenisher'
  AND p.status = 'ACTIVE'
  AND p.perm_code IN (
    'merchant:portal:access',
    'merchant:nav:field',
    'merchant:devices:list',
    'merchant:devices:detail',
    'merchant:slots:view',
    'merchant:inventory:view',
    'merchant:alerts:view',
    'merchant:temp:history',
    'merchant:replenishment:view',
    'merchant:replenishment:request'
  )
ON CONFLICT DO NOTHING;
