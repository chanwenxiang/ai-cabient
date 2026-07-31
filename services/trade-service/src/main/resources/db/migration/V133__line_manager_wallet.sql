-- V133: 线长主数据 + 钱包账本 + 自主提现 + 商户端权限

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 493, 402, 'ops:line-manager:list', '线长钱包', 'C', '/line-managers', 25, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:line-manager:list');

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 494,
       (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:line-manager:list' LIMIT 1),
       'ops:line-manager:edit', '线长编辑', 'F', NULL, 10, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:line-manager:edit');

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 495,
       (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:line-manager:list' LIMIT 1),
       'ops:line-withdraw:review', '线长提现审核', 'F', NULL, 20, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:line-withdraw:review');

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT DISTINCT rp.role_id, p_new.permission_id
FROM ops_permission p_gate
JOIN ops_role_permission rp ON rp.permission_id = p_gate.permission_id
JOIN ops_permission p_new ON p_new.perm_code IN ('ops:line-manager:list', 'ops:line-manager:edit', 'ops:line-withdraw:review')
WHERE p_gate.perm_code IN ('ops:merchant:list', 'ops:finance:view', 'ops:admin')
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code IN ('ops:line-manager:list', 'ops:line-manager:edit', 'ops:line-withdraw:review')
ON CONFLICT DO NOTHING;

-- 商户端：线长钱包（有绑定身份才有业务意义；权限用于导航裁剪）
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 496, 452, 'merchant:line-wallet:view', '线长钱包', 'C', '/merchant/line-wallet', 35, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'merchant:line-wallet:view');

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 6, permission_id FROM ops_permission WHERE perm_code = 'merchant:line-wallet:view'
ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS line_manager (
    manager_id           BIGSERIAL PRIMARY KEY,
    manager_name         VARCHAR(100) NOT NULL,
    phone                VARCHAR(32)  NOT NULL,
    status               VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    wx_openid            VARCHAR(128),
    user_id              BIGINT,
    org_name             VARCHAR(128),
    commission_rate_bps  INT          NOT NULL DEFAULT 200,
    commission_fixed_cents INT        NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_line_manager_phone ON line_manager (phone);
CREATE INDEX IF NOT EXISTS idx_line_manager_user ON line_manager (user_id);

CREATE TABLE IF NOT EXISTS line_device (
    id             BIGSERIAL PRIMARY KEY,
    manager_id     BIGINT       NOT NULL,
    device_id      VARCHAR(64)  NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    assigned_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    unassigned_at  TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_line_device_manager ON line_device (manager_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_line_device_active ON line_device (device_id) WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS line_route (
    id           BIGSERIAL PRIMARY KEY,
    manager_id   BIGINT       NOT NULL,
    route_code   VARCHAR(64)  NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_line_route_manager ON line_route (manager_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_line_route_active ON line_route (manager_id, route_code) WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS line_wallet_account (
    manager_id     BIGINT PRIMARY KEY,
    balance_cents  BIGINT NOT NULL DEFAULT 0,
    frozen_cents   BIGINT NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS line_wallet_ledger (
    ledger_id       BIGSERIAL PRIMARY KEY,
    manager_id      BIGINT       NOT NULL,
    entry_type      VARCHAR(32)  NOT NULL,
    amount_cents    BIGINT       NOT NULL,
    balance_after   BIGINT       NOT NULL,
    frozen_after    BIGINT       NOT NULL DEFAULT 0,
    ref_type        VARCHAR(32),
    ref_id          VARCHAR(64),
    remark          VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_line_wallet_ledger_manager
    ON line_wallet_ledger (manager_id, created_at DESC);

CREATE TABLE IF NOT EXISTS line_withdraw_request (
    request_id      BIGSERIAL PRIMARY KEY,
    request_no      VARCHAR(64)  NOT NULL,
    manager_id      BIGINT       NOT NULL,
    amount_cents    BIGINT       NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING_REVIEW',
    pay_channel     VARCHAR(32)  NOT NULL DEFAULT 'MOCK',
    reviewer_id     BIGINT,
    review_remark   VARCHAR(255),
    reviewed_at     TIMESTAMPTZ,
    payout_ref      VARCHAR(128),
    payout_message  VARCHAR(255),
    paid_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_line_withdraw_request_no ON line_withdraw_request (request_no);
CREATE INDEX IF NOT EXISTS idx_line_withdraw_manager ON line_withdraw_request (manager_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_line_withdraw_status ON line_withdraw_request (status, created_at DESC);

COMMENT ON COLUMN line_withdraw_request.status IS 'PENDING_REVIEW|APPROVED|PAYING|PAID|REJECTED|FAILED';

CREATE TABLE IF NOT EXISTS line_commission_daily (
    id                 BIGSERIAL PRIMARY KEY,
    manager_id         BIGINT       NOT NULL,
    biz_date           DATE         NOT NULL,
    device_id          VARCHAR(64)  NOT NULL,
    order_count        INT          NOT NULL DEFAULT 0,
    gmv_cents          BIGINT       NOT NULL DEFAULT 0,
    commission_cents   BIGINT       NOT NULL DEFAULT 0,
    status             VARCHAR(16)  NOT NULL DEFAULT 'POSTED',
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_line_commission_daily
    ON line_commission_daily (manager_id, biz_date, device_id);

-- 演示：绑定商户管理员 13800138001 为线长，绑 CAB-001，预存余额 100 元
INSERT INTO line_manager (manager_name, phone, status, user_id, org_name, commission_rate_bps, wx_openid)
SELECT '演示线长', '13800138001', 'ACTIVE', 100000002, '演示组织', 200, 'demo-openid-line-001'
WHERE NOT EXISTS (SELECT 1 FROM line_manager WHERE phone = '13800138001');

INSERT INTO line_device (manager_id, device_id, status)
SELECT m.manager_id, 'CAB-001', 'ACTIVE'
FROM line_manager m
WHERE m.phone = '13800138001'
  AND EXISTS (SELECT 1 FROM device_info WHERE device_id = 'CAB-001')
  AND NOT EXISTS (
      SELECT 1 FROM line_device d WHERE d.device_id = 'CAB-001' AND d.status = 'ACTIVE'
  );

INSERT INTO line_wallet_account (manager_id, balance_cents, frozen_cents, updated_at)
SELECT m.manager_id, 10000, 0, NOW()
FROM line_manager m
WHERE m.phone = '13800138001'
ON CONFLICT (manager_id) DO NOTHING;

INSERT INTO line_wallet_ledger (manager_id, entry_type, amount_cents, balance_after, frozen_after, ref_type, ref_id, remark)
SELECT m.manager_id, 'ADJUST', 10000, 10000, 0, 'SEED', 'demo-seed', '演示初始余额'
FROM line_manager m
WHERE m.phone = '13800138001'
  AND NOT EXISTS (
      SELECT 1 FROM line_wallet_ledger l WHERE l.manager_id = m.manager_id AND l.ref_id = 'demo-seed'
  );

SELECT setval(
    pg_get_serial_sequence('ops_permission', 'permission_id'),
    GREATEST((SELECT COALESCE(MAX(permission_id), 1) FROM ops_permission), 1)
);
