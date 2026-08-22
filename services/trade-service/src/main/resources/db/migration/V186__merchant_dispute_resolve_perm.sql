-- merchant dispute resolve (video + limited settle)
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order)
SELECT COALESCE((SELECT MAX(permission_id) FROM ops_permission), 0) + 1,
       (SELECT permission_id FROM ops_permission WHERE perm_code = 'merchant:disputes:list' LIMIT 1),
       'merchant:disputes:resolve',
       '争议结案',
       'C',
       '/merchant/disputes',
       23
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'merchant:disputes:resolve');

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 6, permission_id FROM ops_permission WHERE perm_code = 'merchant:disputes:resolve'
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 10, permission_id FROM ops_permission WHERE perm_code = 'merchant:disputes:resolve'
ON CONFLICT DO NOTHING;
