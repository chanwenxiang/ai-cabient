-- V228: Configurable approval workflow + ops inbox tasks.

CREATE TABLE IF NOT EXISTS approval_definition (
    def_id       BIGSERIAL PRIMARY KEY,
    biz_type     VARCHAR(32)  NOT NULL UNIQUE,
    def_name     VARCHAR(64)  NOT NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    remark       VARCHAR(256),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS approval_node (
    node_id         BIGSERIAL PRIMARY KEY,
    def_id          BIGINT       NOT NULL REFERENCES approval_definition (def_id) ON DELETE CASCADE,
    seq             INT          NOT NULL,
    node_name       VARCHAR(64)  NOT NULL,
    assignee_type   VARCHAR(16)  NOT NULL,
    assignee_value  VARCHAR(64)  NOT NULL,
    pass_rule       VARCHAR(16)  NOT NULL DEFAULT 'ANY',
    UNIQUE (def_id, seq)
);

CREATE TABLE IF NOT EXISTS approval_instance (
    instance_id       BIGSERIAL PRIMARY KEY,
    def_id            BIGINT       NOT NULL REFERENCES approval_definition (def_id),
    biz_type          VARCHAR(32)  NOT NULL,
    biz_id            VARCHAR(64)  NOT NULL,
    title             VARCHAR(160) NOT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    submitter_id      BIGINT,
    current_node_seq  INT          NOT NULL DEFAULT 1,
    remark            TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    finished_at       TIMESTAMPTZ,
    UNIQUE (biz_type, biz_id)
);

CREATE INDEX IF NOT EXISTS idx_approval_instance_status ON approval_instance (status, created_at DESC);

CREATE TABLE IF NOT EXISTS approval_task (
    task_id            BIGSERIAL PRIMARY KEY,
    instance_id        BIGINT       NOT NULL REFERENCES approval_instance (instance_id) ON DELETE CASCADE,
    node_seq           INT          NOT NULL,
    node_name          VARCHAR(64)  NOT NULL,
    assignee_user_id   BIGINT       NOT NULL REFERENCES user_info (user_id),
    status             VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    remark             TEXT,
    acted_at           TIMESTAMPTZ,
    read_at            TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_approval_task_assignee ON approval_task (assignee_user_id, status, created_at DESC);

COMMENT ON TABLE approval_definition IS 'Approval flow definition by biz_type';
COMMENT ON TABLE approval_node IS 'Ordered nodes: assignee_type=PERM|ROLE';
COMMENT ON TABLE approval_instance IS 'Running/completed approval for a business document';
COMMENT ON TABLE approval_task IS 'Per-approver todo; ANY rule = one approve completes node';

INSERT INTO approval_definition (def_id, biz_type, def_name, remark)
VALUES
    (1, 'MERCHANT_REPLEN_REQUEST', '商户要货审批', '商户提交要货 -> 运营接单/驳回'),
    (2, 'PURCHASE_ORDER', '采购单审批', '线长/仓管提交 -> 财务或采购审批后下单')
ON CONFLICT (biz_type) DO NOTHING;

INSERT INTO approval_node (def_id, seq, node_name, assignee_type, assignee_value, pass_rule)
SELECT 1, 1, '运营审核', 'PERM', 'ops:replenishment:edit', 'ANY'
WHERE NOT EXISTS (SELECT 1 FROM approval_node WHERE def_id = 1 AND seq = 1);

INSERT INTO approval_node (def_id, seq, node_name, assignee_type, assignee_value, pass_rule)
SELECT 2, 1, '采购申请', 'PERM', 'ops:warehouse:edit', 'ANY'
WHERE NOT EXISTS (SELECT 1 FROM approval_node WHERE def_id = 2 AND seq = 1);

INSERT INTO approval_node (def_id, seq, node_name, assignee_type, assignee_value, pass_rule)
SELECT 2, 2, '财务审批', 'ROLE', 'finance', 'ANY'
WHERE NOT EXISTS (SELECT 1 FROM approval_node WHERE def_id = 2 AND seq = 2);

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
VALUES
    (650, 402, 'ops:approval:list', '审批待办', 'C', '/approvals', 135, 'ACTIVE'),
    (651, 650, 'ops:approval:config', '审批流配置', 'F', NULL, 1, 'ACTIVE')
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM ops_role r
CROSS JOIN ops_permission p
WHERE r.role_key IN ('admin', 'operator', 'replenisher', 'finance')
  AND p.perm_code IN ('ops:approval:list')
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission WHERE perm_code = 'ops:approval:config'
ON CONFLICT DO NOTHING;

INSERT INTO notification_template (template_code, template_name, title_template, body_template, channel, channels, audience, category, status)
SELECT 'ops_approval_pending', '运营审批待办', '待审批：{title}', '{body}', 'IN_APP', 'IN_APP', 'OPS', 'APPROVAL', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM notification_template WHERE template_code = 'ops_approval_pending');

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
SELECT 'approval_biz_type', '审批业务类型', 'ACTIVE', 0, 'approval_instance.biz_type'
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'approval_biz_type');

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status, remark)
SELECT v.dict_type, v.dict_value, v.dict_label, v.sort_order, 'ACTIVE', v.remark
FROM (VALUES
    ('approval_biz_type', 'MERCHANT_REPLEN_REQUEST', '商户要货', 1, 'replenishment requests'),
    ('approval_biz_type', 'PURCHASE_ORDER', '采购单', 2, 'warehouse purchase orders')
) AS v(dict_type, dict_value, dict_label, sort_order, remark)
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data d WHERE d.dict_type = v.dict_type AND d.dict_value = v.dict_value
);

SELECT setval(pg_get_serial_sequence('ops_permission', 'permission_id'), GREATEST((SELECT COALESCE(MAX(permission_id), 1) FROM ops_permission), 1));
SELECT setval(pg_get_serial_sequence('approval_definition', 'def_id'), GREATEST((SELECT COALESCE(MAX(def_id), 1) FROM approval_definition), 1));
