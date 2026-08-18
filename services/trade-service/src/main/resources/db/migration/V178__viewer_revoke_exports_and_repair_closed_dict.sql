-- 收紧 viewer：去掉资金/库存健康导出等写/导出 F 权限（BUG-009）
DELETE FROM ops_role_permission rp
USING ops_role r, ops_permission p
WHERE rp.role_id = r.role_id
  AND rp.permission_id = p.permission_id
  AND r.role_key = 'viewer'
  AND p.perm_code IN (
    'ops:fund:export',
    'ops:stock-health:export',
    'ops:order:export',
    'ops:session:export',
    'ops:device:export',
    'ops:sku:export',
    'ops:exception:export',
    'ops:dispute:export',
    'ops:feedback:export',
    'ops:reconciliation:export',
    'ops:merchant:export',
    'ops:vision:export',
    'ops:finance:export'
  );

-- 维修工单历史 CLOSED 状态字典（BUG-023）
INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status, remark)
SELECT 'repair_ticket_status', 'CLOSED', '已关闭', 50, 'ACTIVE', 'legacy closed'
WHERE EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'repair_ticket_status')
  AND NOT EXISTS (
  SELECT 1 FROM sys_dict_data WHERE dict_type = 'repair_ticket_status' AND dict_value = 'CLOSED'
);
