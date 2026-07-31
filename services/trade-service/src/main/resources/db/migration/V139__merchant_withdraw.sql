-- V139: 商户钱包账本 + 自主提现（Mock 打款）+ 运营/商户权限

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 497, 402, 'ops:merchant-withdraw:list', '商户提现', 'C', '/merchant-withdraw', 26, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:merchant-withdraw:list');

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 498,
       (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:merchant-withdraw:list' LIMIT 1),
       'ops:merchant-withdraw:adjust', '商户钱包调账', 'F', NULL, 10, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:merchant-withdraw:adjust');

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 499,
       (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:merchant-withdraw:list' LIMIT 1),
       'ops:merchant-withdraw:review', '商户提现审核', 'F', NULL, 20, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:merchant-withdraw:review');

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT DISTINCT rp.role_id, p_new.permission_id
FROM ops_permission p_gate
JOIN ops_role_permission rp ON rp.permission_id = p_gate.permission_id
JOIN ops_permission p_new ON p_new.perm_code IN (
    'ops:merchant-withdraw:list', 'ops:merchant-withdraw:adjust', 'ops:merchant-withdraw:review')
WHERE p_gate.perm_code IN ('ops:merchant:list', 'ops:finance:view', 'ops:admin', 'ops:line-manager:list')
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code IN (
    'ops:merchant-withdraw:list', 'ops:merchant-withdraw:adjust', 'ops:merchant-withdraw:review')
ON CONFLICT DO NOTHING;

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 500, 452, 'merchant:wallet:view', '商户钱包', 'C', '/merchant/wallet', 30, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'merchant:wallet:view');

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 501,
       (SELECT permission_id FROM ops_permission WHERE perm_code = 'merchant:wallet:view' LIMIT 1),
       'merchant:wallet:apply', '申请提现', 'F', NULL, 10, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'merchant:wallet:apply');

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 6, permission_id FROM ops_permission
WHERE perm_code IN ('merchant:wallet:view', 'merchant:wallet:apply')
ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS merchant_wallet_account (
    merchant_id    VARCHAR(64) PRIMARY KEY,
    balance_cents  BIGINT NOT NULL DEFAULT 0,
    frozen_cents   BIGINT NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS merchant_wallet_ledger (
    ledger_id       BIGSERIAL PRIMARY KEY,
    merchant_id     VARCHAR(64)  NOT NULL,
    entry_type      VARCHAR(32)  NOT NULL,
    amount_cents    BIGINT       NOT NULL,
    balance_after   BIGINT       NOT NULL,
    frozen_after    BIGINT       NOT NULL DEFAULT 0,
    ref_type        VARCHAR(32),
    ref_id          VARCHAR(64),
    remark          VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_merchant_wallet_ledger_merchant
    ON merchant_wallet_ledger (merchant_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_merchant_wallet_ledger_ref
    ON merchant_wallet_ledger (merchant_id, ref_type, ref_id);

CREATE TABLE IF NOT EXISTS merchant_withdraw_request (
    request_id      BIGSERIAL PRIMARY KEY,
    request_no      VARCHAR(64)  NOT NULL,
    merchant_id     VARCHAR(64)  NOT NULL,
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

CREATE UNIQUE INDEX IF NOT EXISTS uk_merchant_withdraw_request_no ON merchant_withdraw_request (request_no);
CREATE INDEX IF NOT EXISTS idx_merchant_withdraw_merchant ON merchant_withdraw_request (merchant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_merchant_withdraw_status ON merchant_withdraw_request (status, created_at DESC);

COMMENT ON COLUMN merchant_withdraw_request.status IS 'PENDING_REVIEW|APPROVED|PAYING|PAID|REJECTED|FAILED';

-- 演示：默认商户预存可提现余额 500 元
INSERT INTO merchant_wallet_account (merchant_id, balance_cents, frozen_cents, updated_at)
SELECT 'MCH-DEFAULT', 50000, 0, NOW()
WHERE EXISTS (SELECT 1 FROM merchant WHERE merchant_id = 'MCH-DEFAULT')
ON CONFLICT (merchant_id) DO NOTHING;

INSERT INTO merchant_wallet_ledger (merchant_id, entry_type, amount_cents, balance_after, frozen_after, ref_type, ref_id, remark)
SELECT 'MCH-DEFAULT', 'ADJUST', 50000, 50000, 0, 'SEED', 'demo-seed', '演示初始可提现余额'
WHERE EXISTS (SELECT 1 FROM merchant WHERE merchant_id = 'MCH-DEFAULT')
  AND NOT EXISTS (
      SELECT 1 FROM merchant_wallet_ledger l
      WHERE l.merchant_id = 'MCH-DEFAULT' AND l.ref_id = 'demo-seed'
  );

SELECT setval(
    pg_get_serial_sequence('ops_permission', 'permission_id'),
    GREATEST((SELECT COALESCE(MAX(permission_id), 1) FROM ops_permission), 1)
);
