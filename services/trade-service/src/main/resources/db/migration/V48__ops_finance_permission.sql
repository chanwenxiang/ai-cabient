-- Finance reports contain platform-sensitive revenue and cost data and must not reuse replenishment permissions.
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order)
VALUES (103, 100, 'ops:finance:view', '经营财务报表', 'C', '/admin/finance', 3)
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT role_id, 103 FROM ops_role WHERE role_key IN ('admin', 'finance')
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT role_id, 10 FROM ops_role WHERE role_key = 'finance'
ON CONFLICT DO NOTHING;
