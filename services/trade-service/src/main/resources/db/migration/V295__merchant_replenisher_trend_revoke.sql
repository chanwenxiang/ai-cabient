-- 权限口径最终确认（2026-09-30）：营业额/趋势仅商户店长可见（财务/商户管理员原有不变）。
-- 撤销 V294 给商户补货员的趋势查看授权；店员的已在 V294 回收。
DELETE FROM ops_role_permission
WHERE permission_id = (SELECT permission_id FROM ops_permission WHERE perm_code = 'merchant:trend:view')
  AND role_id = (SELECT role_id FROM ops_role WHERE role_key = 'merchant_replenisher');
