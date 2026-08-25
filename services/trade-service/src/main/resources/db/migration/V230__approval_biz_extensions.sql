-- V230: Extend approval workflow to purchase / withdraw / balance refund / wallet adjust.

-- Fix purchase node 1 to match procurement permission code.
UPDATE approval_node
SET assignee_type = 'PERM', assignee_value = 'ops:procurement:edit'
WHERE def_id = 2 AND seq = 1 AND assignee_value = 'ops:warehouse:edit';

INSERT INTO approval_definition (biz_type, def_name, remark)
VALUES
    ('MERCHANT_WITHDRAW', '商户提现审批', '运营审核 -> 财务复核后打款'),
    ('LINE_WITHDRAW', '线长提现审批', '运营审核 -> 财务复核后打款'),
    ('BALANCE_REFUND', '余额退款审批', '运营审核 -> 财务复核后原路退款'),
    ('MERCHANT_WALLET_ADJUST', '商户大额调账', '财务知会（调账已执行）')
ON CONFLICT (biz_type) DO NOTHING;

INSERT INTO approval_node (def_id, seq, node_name, assignee_type, assignee_value, pass_rule)
SELECT d.def_id, 1, '运营审核', 'PERM', 'ops:merchant-withdraw:review', 'ANY'
FROM approval_definition d
WHERE d.biz_type = 'MERCHANT_WITHDRAW'
  AND NOT EXISTS (SELECT 1 FROM approval_node n WHERE n.def_id = d.def_id AND n.seq = 1);

INSERT INTO approval_node (def_id, seq, node_name, assignee_type, assignee_value, pass_rule)
SELECT d.def_id, 2, '财务复核', 'ROLE', 'finance', 'ANY'
FROM approval_definition d
WHERE d.biz_type = 'MERCHANT_WITHDRAW'
  AND NOT EXISTS (SELECT 1 FROM approval_node n WHERE n.def_id = d.def_id AND n.seq = 2);

INSERT INTO approval_node (def_id, seq, node_name, assignee_type, assignee_value, pass_rule)
SELECT d.def_id, 1, '运营审核', 'PERM', 'ops:line-withdraw:review', 'ANY'
FROM approval_definition d
WHERE d.biz_type = 'LINE_WITHDRAW'
  AND NOT EXISTS (SELECT 1 FROM approval_node n WHERE n.def_id = d.def_id AND n.seq = 1);

INSERT INTO approval_node (def_id, seq, node_name, assignee_type, assignee_value, pass_rule)
SELECT d.def_id, 2, '财务复核', 'ROLE', 'finance', 'ANY'
FROM approval_definition d
WHERE d.biz_type = 'LINE_WITHDRAW'
  AND NOT EXISTS (SELECT 1 FROM approval_node n WHERE n.def_id = d.def_id AND n.seq = 2);

INSERT INTO approval_node (def_id, seq, node_name, assignee_type, assignee_value, pass_rule)
SELECT d.def_id, 1, '运营审核', 'PERM', 'ops:balance-refund:review', 'ANY'
FROM approval_definition d
WHERE d.biz_type = 'BALANCE_REFUND'
  AND NOT EXISTS (SELECT 1 FROM approval_node n WHERE n.def_id = d.def_id AND n.seq = 1);

INSERT INTO approval_node (def_id, seq, node_name, assignee_type, assignee_value, pass_rule)
SELECT d.def_id, 2, '财务复核', 'ROLE', 'finance', 'ANY'
FROM approval_definition d
WHERE d.biz_type = 'BALANCE_REFUND'
  AND NOT EXISTS (SELECT 1 FROM approval_node n WHERE n.def_id = d.def_id AND n.seq = 2);

INSERT INTO approval_node (def_id, seq, node_name, assignee_type, assignee_value, pass_rule)
SELECT d.def_id, 1, '财务知会', 'ROLE', 'finance', 'ANY'
FROM approval_definition d
WHERE d.biz_type = 'MERCHANT_WALLET_ADJUST'
  AND NOT EXISTS (SELECT 1 FROM approval_node n WHERE n.def_id = d.def_id AND n.seq = 1);

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status, remark)
SELECT v.dict_type, v.dict_value, v.dict_label, v.sort_order, 'ACTIVE', v.remark
FROM (VALUES
    ('approval_biz_type', 'MERCHANT_WITHDRAW', '商户提现', 3, 'merchant withdraw'),
    ('approval_biz_type', 'LINE_WITHDRAW', '线长提现', 4, 'line withdraw'),
    ('approval_biz_type', 'BALANCE_REFUND', '余额退款', 5, 'balance refund'),
    ('approval_biz_type', 'MERCHANT_WALLET_ADJUST', '商户调账', 6, 'wallet adjust notify')
) AS v(dict_type, dict_value, dict_label, sort_order, remark)
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data d WHERE d.dict_type = v.dict_type AND d.dict_value = v.dict_value
);

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM ops_role r
CROSS JOIN ops_permission p
WHERE r.role_key = 'finance'
  AND p.perm_code IN (
      'ops:merchant-withdraw:review',
      'ops:line-withdraw:review',
      'ops:balance-refund:review',
      'ops:procurement:edit',
      'ops:approval:list'
  )
ON CONFLICT DO NOTHING;
