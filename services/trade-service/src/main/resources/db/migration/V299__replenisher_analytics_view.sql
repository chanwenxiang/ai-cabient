-- MIGRATION_KIND: backfill
-- F1-UX：补货员工作台「销售情况」——①放开经营分析只读权限；展示开关=权限页 merchant:analytics:view 勾选项
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM ops_role r, ops_permission p
WHERE r.role_key = 'merchant_replenisher'
  AND p.perm_code = 'merchant:analytics:view'
  AND NOT EXISTS (
    SELECT 1 FROM ops_role_permission rp
    WHERE rp.role_id = r.role_id AND rp.permission_id = p.permission_id
);
