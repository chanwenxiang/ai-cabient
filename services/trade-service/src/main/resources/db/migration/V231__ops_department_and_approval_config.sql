-- V231: Ops departments for approval assignees + onboarding flow + config menus.

CREATE TABLE IF NOT EXISTS ops_department (
    dept_id     BIGSERIAL PRIMARY KEY,
    dept_key    VARCHAR(32)  NOT NULL UNIQUE,
    dept_name   VARCHAR(64)  NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    remark      VARCHAR(256),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS ops_user_department (
    user_id BIGINT NOT NULL REFERENCES user_info (user_id) ON DELETE CASCADE,
    dept_id BIGINT NOT NULL REFERENCES ops_department (dept_id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, dept_id)
);

CREATE INDEX IF NOT EXISTS idx_ops_user_department_dept ON ops_user_department (dept_id);

COMMENT ON TABLE ops_department IS '运营审批用部门（财务/总部/采购/经理等）';
COMMENT ON TABLE ops_user_department IS '运营账号所属部门（可多选）';

INSERT INTO ops_department (dept_key, dept_name, sort_order, remark)
VALUES
    ('HQ', '总部', 10, '总部综合审批'),
    ('FINANCE', '财务部', 20, '提现/退款/采购财务节点'),
    ('PROCUREMENT', '采购部', 30, '采购申请节点'),
    ('MANAGER', '经理', 40, '经理复核节点')
ON CONFLICT (dept_key) DO NOTHING;

-- Demo accounts into departments
INSERT INTO ops_user_department (user_id, dept_id)
SELECT u.user_id, d.dept_id
FROM user_info u
CROSS JOIN ops_department d
WHERE u.phone_number = '13900000001'
  AND d.dept_key IN ('HQ', 'PROCUREMENT', 'MANAGER')
ON CONFLICT DO NOTHING;

INSERT INTO ops_user_department (user_id, dept_id)
SELECT u.user_id, d.dept_id
FROM user_info u
CROSS JOIN ops_department d
WHERE u.phone_number = '13900000002'
  AND d.dept_key = 'FINANCE'
ON CONFLICT DO NOTHING;

-- Prefer department assignees for money/procurement flows (still configurable in UI).
UPDATE approval_node n
SET assignee_type = 'DEPT', assignee_value = 'PROCUREMENT'
FROM approval_definition d
WHERE n.def_id = d.def_id AND d.biz_type = 'PURCHASE_ORDER' AND n.seq = 1;

UPDATE approval_node n
SET assignee_type = 'DEPT', assignee_value = 'FINANCE'
FROM approval_definition d
WHERE n.def_id = d.def_id AND d.biz_type = 'PURCHASE_ORDER' AND n.seq = 2;

UPDATE approval_node n
SET assignee_type = 'DEPT', assignee_value = 'MANAGER'
FROM approval_definition d
WHERE n.def_id = d.def_id
  AND d.biz_type IN ('MERCHANT_WITHDRAW', 'LINE_WITHDRAW', 'BALANCE_REFUND')
  AND n.seq = 1;

UPDATE approval_node n
SET assignee_type = 'DEPT', assignee_value = 'FINANCE'
FROM approval_definition d
WHERE n.def_id = d.def_id
  AND d.biz_type IN ('MERCHANT_WITHDRAW', 'LINE_WITHDRAW', 'BALANCE_REFUND')
  AND n.seq = 2;

INSERT INTO approval_definition (biz_type, def_name, remark)
VALUES ('MERCHANT_ONBOARD', '商户进件审批', '进件提交 -> 总部/财务审核后生效')
ON CONFLICT (biz_type) DO NOTHING;

INSERT INTO approval_node (def_id, seq, node_name, assignee_type, assignee_value, pass_rule)
SELECT d.def_id, 1, '总部审核', 'DEPT', 'HQ', 'ANY'
FROM approval_definition d
WHERE d.biz_type = 'MERCHANT_ONBOARD'
  AND NOT EXISTS (SELECT 1 FROM approval_node n WHERE n.def_id = d.def_id AND n.seq = 1);

INSERT INTO approval_node (def_id, seq, node_name, assignee_type, assignee_value, pass_rule)
SELECT d.def_id, 2, '财务复核', 'DEPT', 'FINANCE', 'ANY'
FROM approval_definition d
WHERE d.biz_type = 'MERCHANT_ONBOARD'
  AND NOT EXISTS (SELECT 1 FROM approval_node n WHERE n.def_id = d.def_id AND n.seq = 2);

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
VALUES
    (652, 403, 'ops:dept:list', '部门管理', 'C', '/departments', 25, 'ACTIVE'),
    (653, 652, 'ops:dept:edit', '编辑部门', 'F', NULL, 1, 'ACTIVE')
ON CONFLICT (perm_code) DO NOTHING;

-- Promote approval config to system menu page
UPDATE ops_permission
SET parent_id = 403,
    perm_type = 'C',
    path = '/approvals',
    perm_name = '审批流配置',
    sort_order = 26
WHERE perm_code = 'ops:approval:config';

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, p.permission_id
FROM ops_permission p
WHERE p.perm_code IN ('ops:dept:list', 'ops:dept:edit', 'ops:approval:config')
ON CONFLICT DO NOTHING;

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
SELECT 'approval_assignee_type', '审批指派类型', 'ACTIVE', 0, 'approval_node.assignee_type'
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'approval_assignee_type');

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status, remark)
SELECT v.dict_type, v.dict_value, v.dict_label, v.sort_order, 'ACTIVE', v.remark
FROM (VALUES
    ('approval_biz_type', 'MERCHANT_ONBOARD', '商户进件', 7, 'payment onboarding'),
    ('approval_assignee_type', 'PERM', '按权限', 1, 'ops permission code'),
    ('approval_assignee_type', 'ROLE', '按角色', 2, 'ops role_key'),
    ('approval_assignee_type', 'DEPT', '按部门', 3, 'ops_department.dept_key'),
    ('approval_assignee_type', 'USER', '指定用户', 4, 'user_id')
) AS v(dict_type, dict_value, dict_label, sort_order, remark)
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data d WHERE d.dict_type = v.dict_type AND d.dict_value = v.dict_value
);

SELECT setval(pg_get_serial_sequence('ops_permission', 'permission_id'),
              GREATEST((SELECT COALESCE(MAX(permission_id), 1) FROM ops_permission), 1));
SELECT setval(pg_get_serial_sequence('ops_department', 'dept_id'),
              GREATEST((SELECT COALESCE(MAX(dept_id), 1) FROM ops_department), 1));
SELECT setval(pg_get_serial_sequence('approval_definition', 'def_id'),
              GREATEST((SELECT COALESCE(MAX(def_id), 1) FROM approval_definition), 1));
