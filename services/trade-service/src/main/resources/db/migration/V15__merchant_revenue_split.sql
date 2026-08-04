-- 商户与订单分账（账本模式，暂不调用微信分账 API）

CREATE TABLE IF NOT EXISTS merchant (
    merchant_id          VARCHAR(32)  PRIMARY KEY,
    merchant_name        VARCHAR(128) NOT NULL,
    contact_phone        VARCHAR(32),
    platform_rate_bps    INT          NOT NULL DEFAULT 1000,
    wechat_receiver_id   VARCHAR(64),
    status               VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    remark               VARCHAR(256),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

ALTER TABLE device_info
    ADD COLUMN IF NOT EXISTS merchant_id VARCHAR(32) REFERENCES merchant(merchant_id);

CREATE TABLE IF NOT EXISTS order_revenue_split (
    split_id         VARCHAR(32) PRIMARY KEY,
    order_id         VARCHAR(32) NOT NULL REFERENCES cabinet_order(order_id),
    merchant_id      VARCHAR(32) NOT NULL REFERENCES merchant(merchant_id),
    device_id        VARCHAR(64) NOT NULL,
    gross_cents      BIGINT      NOT NULL,
    platform_cents   BIGINT      NOT NULL,
    merchant_cents   BIGINT      NOT NULL,
    status           VARCHAR(16) NOT NULL DEFAULT 'ACCRUED',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_order_split_merchant ON order_revenue_split (merchant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_order_split_order ON order_revenue_split (order_id);

INSERT INTO merchant (merchant_id, merchant_name, platform_rate_bps, remark)
VALUES ('MCH-DEFAULT', '默认直营商户', 1000, '本地演示，平台抽成 10%')
ON CONFLICT (merchant_id) DO NOTHING;

UPDATE device_info SET merchant_id = 'MCH-DEFAULT'
WHERE merchant_id IS NULL;

-- RBAC
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order) VALUES
    (170, 1, 'ops:merchant',           '商户分账',   'M', NULL,                      16),
    (171, 170, 'ops:merchant:list',   '商户列表',   'C', '/admin/merchants',        1),
    (172, 170, 'ops:merchant:edit',   '商户编辑',   'F', NULL,                      2),
    (173, 170, 'ops:merchant:split',  '分账明细',   'C', '/admin/revenue-splits',   3)
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code IN ('ops:merchant', 'ops:merchant:list', 'ops:merchant:edit', 'ops:merchant:split')
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 4, permission_id FROM ops_permission
WHERE perm_code IN ('ops:merchant', 'ops:merchant:list', 'ops:merchant:split')
ON CONFLICT DO NOTHING;
