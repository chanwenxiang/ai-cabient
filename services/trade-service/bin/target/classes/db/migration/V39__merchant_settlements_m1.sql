-- M1 商户对账单：结算查看与导出权限

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order) VALUES
    (218, 200, 'merchant:settlements:view',   '结算对账', 'C', '/merchant/settlements', 18),
    (219, 200, 'merchant:settlements:export', '对账导出', 'C', '/merchant/settlements', 19)
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 6, permission_id FROM ops_permission WHERE perm_code IN ('merchant:settlements:view', 'merchant:settlements:export')
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 7, permission_id FROM ops_permission WHERE perm_code = 'merchant:settlements:view'
ON CONFLICT DO NOTHING;
