-- P0: 优惠券与营销活动系统 — 对标友宝/丰e营销体系

CREATE TABLE IF NOT EXISTS promotion_activity (
    activity_id     BIGSERIAL       PRIMARY KEY,
    activity_name   VARCHAR(128)    NOT NULL,
    activity_type   VARCHAR(32)     NOT NULL,
    status          VARCHAR(16)     NOT NULL DEFAULT 'DRAFT',
    start_time      TIMESTAMPTZ     NOT NULL,
    end_time        TIMESTAMPTZ     NOT NULL,
    budget_cents    BIGINT          NOT NULL DEFAULT 0,
    used_cents      BIGINT          NOT NULL DEFAULT 0,
    user_limit      INT             NOT NULL DEFAULT 1,
    device_scope    VARCHAR(32)     NOT NULL DEFAULT 'ALL',
    rule_config     JSONB           NOT NULL DEFAULT '{}',
    description     TEXT,
    operator_id     BIGINT,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_promotion_status_time ON promotion_activity (status, start_time, end_time);

CREATE TABLE IF NOT EXISTS promotion_device (
    activity_id     BIGINT          NOT NULL REFERENCES promotion_activity(activity_id) ON DELETE CASCADE,
    device_id       VARCHAR(64)     NOT NULL REFERENCES device_info(device_id),
    PRIMARY KEY (activity_id, device_id)
);

CREATE TABLE IF NOT EXISTS coupon_definition (
    coupon_def_id   BIGSERIAL       PRIMARY KEY,
    coupon_name     VARCHAR(128)    NOT NULL,
    coupon_type     VARCHAR(32)     NOT NULL,
    denomination_cents INT          NOT NULL,
    min_spend_cents INT             NOT NULL DEFAULT 0,
    discount_percent INT,
    validity_days   INT             NOT NULL DEFAULT 30,
    max_issue_count INT             NOT NULL DEFAULT 0,
    issued_count    INT             NOT NULL DEFAULT 0,
    activity_id     BIGINT          REFERENCES promotion_activity(activity_id),
    device_scope    VARCHAR(32)     NOT NULL DEFAULT 'ALL',
    status          VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    description     TEXT,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_coupon_def_status ON coupon_definition (status, created_at DESC);

CREATE TABLE IF NOT EXISTS user_coupon (
    coupon_id       BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT          NOT NULL REFERENCES user_info(user_id),
    coupon_def_id   BIGINT          NOT NULL REFERENCES coupon_definition(coupon_def_id),
    coupon_code     VARCHAR(32)     NOT NULL UNIQUE,
    status          VARCHAR(16)     NOT NULL DEFAULT 'UNUSED',
    received_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    used_at         TIMESTAMPTZ,
    expire_at       TIMESTAMPTZ     NOT NULL,
    order_id        VARCHAR(32)     REFERENCES cabinet_order(order_id),
    device_id       VARCHAR(64),
    discount_cents  INT,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_coupon_user ON user_coupon (user_id, status);
CREATE INDEX IF NOT EXISTS idx_user_coupon_code ON user_coupon (coupon_code);
CREATE INDEX IF NOT EXISTS idx_user_coupon_expire ON user_coupon (status, expire_at) WHERE status = 'UNUSED';
CREATE INDEX IF NOT EXISTS idx_user_coupon_order ON user_coupon (order_id);

ALTER TABLE cabinet_order
    ADD COLUMN IF NOT EXISTS coupon_id BIGINT,
    ADD COLUMN IF NOT EXISTS coupon_discount_cents INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS promotion_id BIGINT,
    ADD COLUMN IF NOT EXISTS member_discount_cents INT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_cabinet_order_coupon ON cabinet_order (coupon_id) WHERE coupon_id IS NOT NULL;

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order) VALUES
    (240, 1, 'ops:promotion',           '营销管理',   'M', NULL,                  19),
    (241, 240, 'ops:promotion:list',    '活动列表',   'C', '/admin/promotions',   1),
    (242, 240, 'ops:promotion:create',  '新建活动',   'F', NULL,                  2),
    (243, 240, 'ops:promotion:edit',    '编辑活动',   'F', NULL,                  3),
    (244, 240, 'ops:promotion:stop',    '停止活动',   'F', NULL,                  4),
    (245, 240, 'ops:coupon:list',       '优惠券管理',  'C', '/admin/coupons',     5),
    (246, 240, 'ops:coupon:create',     '发券',       'F', NULL,                  6),
    (247, 240, 'ops:coupon:export',     '导出券码',   'F', NULL,                  7)
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission WHERE perm_code LIKE 'ops:promotion%' OR perm_code LIKE 'ops:coupon%'
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 2, permission_id FROM ops_permission WHERE perm_code IN ('ops:promotion', 'ops:promotion:list', 'ops:promotion:create')
ON CONFLICT DO NOTHING;

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order) VALUES
    (250, 200, 'merchant:coupon:view', '优惠券查看', 'C', '/merchant/coupons', 30)
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 6, permission_id FROM ops_permission WHERE perm_code = 'merchant:coupon:view'
ON CONFLICT DO NOTHING;
