-- Wave1: payment onboarding + device scope ALL|DEVICE_IDS|ROUTE + route allowlist + perms

CREATE TABLE IF NOT EXISTS merchant_payment_onboarding (
    onboarding_id   BIGSERIAL PRIMARY KEY,
    merchant_id     VARCHAR(64)  NOT NULL,
    channel         VARCHAR(16)  NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    external_mch_id VARCHAR(128),
    external_ref    VARCHAR(256),
    note            VARCHAR(512),
    last_synced_at  TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_merchant_channel UNIQUE (merchant_id, channel),
    CONSTRAINT chk_onboard_channel CHECK (channel IN ('WECHAT', 'ALIPAY', 'PAYSCORE')),
    CONSTRAINT chk_onboard_status CHECK (status IN ('DRAFT', 'SUBMITTED', 'ACTIVE', 'REJECTED'))
);
CREATE INDEX IF NOT EXISTS idx_mpo_status ON merchant_payment_onboarding (status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_mpo_merchant ON merchant_payment_onboarding (merchant_id);

-- PARTIAL → DEVICE_IDS; support ROUTE via route codes
UPDATE ops_user_device_scope_pref
SET scope_mode = 'DEVICE_IDS'
WHERE UPPER(scope_mode) = 'PARTIAL';

CREATE TABLE IF NOT EXISTS ops_user_route_scope (
    user_id    BIGINT      NOT NULL,
    route_code VARCHAR(64) NOT NULL,
    PRIMARY KEY (user_id, route_code)
);

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT COALESCE((SELECT MAX(permission_id) FROM ops_permission), 0) + 1,
       (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:merchant:list' LIMIT 1),
       'ops:merchant:onboard:list', '进件工作台', 'C', '/merchant-onboarding', 56, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:merchant:onboard:list');

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT COALESCE((SELECT MAX(permission_id) FROM ops_permission), 0) + 1,
       (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:merchant:onboard:list' LIMIT 1),
       'ops:merchant:onboard:edit', '进件编辑', 'F', NULL, 1, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:merchant:onboard:edit');

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT r.role_id, p_new.permission_id
FROM ops_role r
CROSS JOIN ops_permission p_new
WHERE p_new.perm_code IN ('ops:merchant:onboard:list', 'ops:merchant:onboard:edit')
  AND r.role_key IN ('admin', 'operator', 'finance')
  AND NOT EXISTS (
    SELECT 1 FROM ops_role_permission rp
    WHERE rp.role_id = r.role_id AND rp.permission_id = p_new.permission_id
  );

UPDATE ops_permission
SET path = '/merchant-onboarding', perm_type = 'C', perm_name = '进件工作台'
WHERE perm_code = 'ops:merchant:onboard:list';
