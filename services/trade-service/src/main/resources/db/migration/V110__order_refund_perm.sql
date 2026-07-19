-- 订单退款按钮权限（与争议裁决解耦）
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order)
SELECT 446,
       (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:order:list' LIMIT 1),
       'ops:order:refund',
       '订单退款',
       'F',
       NULL,
       10
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:order:refund');

-- 曾有争议裁决权的角色自动获得订单退款权
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT rp.role_id, p_refund.permission_id
FROM ops_permission p_dispute
JOIN ops_role_permission rp ON rp.permission_id = p_dispute.permission_id
JOIN ops_permission p_refund ON p_refund.perm_code = 'ops:order:refund'
WHERE p_dispute.perm_code = 'ops:dispute:resolve'
ON CONFLICT DO NOTHING;

SELECT setval(
    pg_get_serial_sequence('ops_permission', 'permission_id'),
    GREATEST((SELECT COALESCE(MAX(permission_id), 1) FROM ops_permission), 1)
);
