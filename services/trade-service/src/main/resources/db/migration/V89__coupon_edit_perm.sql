-- 优惠券启停权限（对齐若依按钮权限 F）
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order)
VALUES (248, 240, 'ops:coupon:edit', '启停优惠券', 'F', NULL, 8)
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission WHERE perm_code = 'ops:coupon:edit'
ON CONFLICT DO NOTHING;
