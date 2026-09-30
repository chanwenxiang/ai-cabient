-- 商户补货员也可在工作台看到今日营收/趋势（首页 KPI 卡对补货员一直隐藏，2026-09-30 用户反馈）。
-- 仅授趋势查看；商户收入分成等敏感 KPI 仍由 canFinanceKpi 其余入口（结算对账等）另行控制。
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM ops_role r
JOIN ops_permission p ON p.perm_code = 'merchant:trend:view'
WHERE r.role_key = 'merchant_replenisher'
  AND NOT EXISTS (
    SELECT 1 FROM ops_role_permission rp
    WHERE rp.role_id = r.role_id AND rp.permission_id = p.permission_id
  );
