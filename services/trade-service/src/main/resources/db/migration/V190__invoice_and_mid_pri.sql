-- consumer invoice requests + merchant tax profile + mid-pri configs
CREATE TABLE IF NOT EXISTS invoice_request (
    invoice_id      BIGSERIAL PRIMARY KEY,
    order_id        VARCHAR(64)  NOT NULL,
    user_id         BIGINT       NOT NULL,
    title           VARCHAR(128) NOT NULL,
    tax_no          VARCHAR(32),
    email           VARCHAR(128),
    amount_cents    INT          NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    reject_reason   VARCHAR(256),
    issued_at       TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_invoice_user ON invoice_request (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_invoice_status ON invoice_request (status, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uk_invoice_order_active
    ON invoice_request (order_id)
    WHERE status IN ('PENDING', 'ISSUED');

CREATE TABLE IF NOT EXISTS merchant_tax_profile (
    merchant_id   VARCHAR(64) PRIMARY KEY REFERENCES merchant(merchant_id),
    company_name  VARCHAR(128) NOT NULL,
    tax_no        VARCHAR(32)  NOT NULL,
    address       VARCHAR(256),
    bank_name     VARCHAR(128),
    bank_account  VARCHAR(64),
    phone         VARCHAR(32),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO system_config (config_key, config_value, description, updated_at)
VALUES (
  'settlement.empty_auto_complete_no_gravity',
  'false',
  '纯视觉柜无重力字段时是否自动零结（true=零结，false=进争议）',
  NOW()
)
ON CONFLICT (config_key) DO NOTHING;

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT COALESCE((SELECT MAX(permission_id) FROM ops_permission), 0) + 1,
       (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:nav:biz' LIMIT 1),
       'ops:invoice:list', '开票申请', 'C', '/invoices', 55, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:invoice:list');

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT COALESCE((SELECT MAX(permission_id) FROM ops_permission), 0) + 1,
       (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:invoice:list' LIMIT 1),
       'ops:invoice:edit', '开票处理', 'F', NULL, 1, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:invoice:edit');

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT DISTINCT rp.role_id, p_new.permission_id
FROM ops_permission p_gate
JOIN ops_role_permission rp ON rp.permission_id = p_gate.permission_id
JOIN ops_permission p_new ON p_new.perm_code IN ('ops:invoice:list', 'ops:invoice:edit')
WHERE p_gate.perm_code IN ('ops:finance:view', 'ops:order:list', 'ops:admin')
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code IN ('ops:invoice:list', 'ops:invoice:edit')
ON CONFLICT DO NOTHING;
