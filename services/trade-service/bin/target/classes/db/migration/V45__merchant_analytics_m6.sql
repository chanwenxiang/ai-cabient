-- M6 商户 BI 分析权限

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order) VALUES
    (224, 200, 'merchant:analytics:view', '经营分析', 'C', '/merchant/analytics', 24)
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 6, permission_id FROM ops_permission WHERE perm_code = 'merchant:analytics:view'
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 7, permission_id FROM ops_permission WHERE perm_code = 'merchant:analytics:view'
ON CONFLICT DO NOTHING;
