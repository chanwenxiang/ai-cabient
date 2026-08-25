-- V185: 消费者可用余额退款申请（审核后原路退充值渠道）+ 充值单部分退款字段

ALTER TABLE recharge_order
    ADD COLUMN IF NOT EXISTS refunded_cents INT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS balance_refund_request (
    request_id       BIGSERIAL PRIMARY KEY,
    request_no       VARCHAR(64)  NOT NULL,
    user_id          BIGINT       NOT NULL,
    amount_cents     INT          NOT NULL,
    status           VARCHAR(32)  NOT NULL DEFAULT 'PENDING_REVIEW',
    reason           VARCHAR(255),
    review_remark    VARCHAR(255),
    reviewer_id      BIGINT,
    reviewed_at      TIMESTAMPTZ,
    fail_reason      VARCHAR(512),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    refunded_at      TIMESTAMPTZ,
    CONSTRAINT uk_balance_refund_request_no UNIQUE (request_no)
);

CREATE INDEX IF NOT EXISTS idx_balance_refund_request_user
    ON balance_refund_request (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_balance_refund_request_status
    ON balance_refund_request (status, created_at DESC);

CREATE TABLE IF NOT EXISTS balance_refund_allocation (
    allocation_id         BIGSERIAL PRIMARY KEY,
    request_id            BIGINT       NOT NULL,
    recharge_order_id     VARCHAR(64)  NOT NULL,
    amount_cents          INT          NOT NULL,
    channel               VARCHAR(16)  NOT NULL,
    out_refund_no         VARCHAR(64),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_balance_refund_alloc_request
    ON balance_refund_allocation (request_id);

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 510,
       (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:recharge:list' LIMIT 1),
       'ops:balance-refund:list', '余额退款申请', 'C', '/balance-refunds', 27, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:balance-refund:list');

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 511,
       (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:balance-refund:list' LIMIT 1),
       'ops:balance-refund:review', '余额退款审核', 'F', NULL, 10, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:balance-refund:review');

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT DISTINCT rp.role_id, p_new.permission_id
FROM ops_permission p_gate
JOIN ops_role_permission rp ON rp.permission_id = p_gate.permission_id
JOIN ops_permission p_new ON p_new.perm_code IN ('ops:balance-refund:list', 'ops:balance-refund:review')
WHERE p_gate.perm_code IN ('ops:recharge:list', 'ops:recharge:edit', 'ops:finance:view', 'ops:admin')
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code IN ('ops:balance-refund:list', 'ops:balance-refund:review')
ON CONFLICT DO NOTHING;
