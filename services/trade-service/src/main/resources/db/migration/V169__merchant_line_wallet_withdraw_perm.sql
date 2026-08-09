-- V162: 拆分线长钱包"查看"与"提现"权限码
-- 此前 POST /line-wallet/withdraw 复用 merchant:line-wallet:view，语义混淆且无法单独授权。
-- 新增 merchant:line-wallet:withdraw 并仅授予商户管理员（角色 6），与前端"仅管理员可提现"预期一致。

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 505, 452, 'merchant:line-wallet:withdraw', '线长钱包提现', 'F', NULL, 36, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'merchant:line-wallet:withdraw');

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 6, permission_id FROM ops_permission WHERE perm_code = 'merchant:line-wallet:withdraw'
ON CONFLICT DO NOTHING;
