-- V125: 竞品缺口能力 —— 资金账单/毛利固化、柜范围、组织运营配置、设备策略、验证流水、进件/储值/算力

-- 毛利日固化
CREATE TABLE IF NOT EXISTS finance_margin_daily_lock (
    biz_date         DATE         PRIMARY KEY,
    revenue_cents    BIGINT       NOT NULL DEFAULT 0,
    cogs_cents       BIGINT       NOT NULL DEFAULT 0,
    margin_cents     BIGINT       NOT NULL DEFAULT 0,
    write_off_cents  BIGINT       NOT NULL DEFAULT 0,
    order_count      BIGINT       NOT NULL DEFAULT 0,
    locked_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    locked_by        BIGINT
);

-- 运营账号货柜范围（PARTIAL 时生效；无行且 mode=ALL 表示商户范围内全部柜）
CREATE TABLE IF NOT EXISTS ops_user_device_scope (
    user_id     BIGINT       NOT NULL,
    device_id   VARCHAR(64)  NOT NULL,
    PRIMARY KEY (user_id, device_id)
);
CREATE INDEX IF NOT EXISTS idx_ops_user_device_scope_device ON ops_user_device_scope (device_id);

CREATE TABLE IF NOT EXISTS ops_user_device_scope_pref (
    user_id     BIGINT       PRIMARY KEY,
    scope_mode  VARCHAR(16)  NOT NULL DEFAULT 'ALL', -- ALL | PARTIAL
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 组织级运营配置（备货/理货）
CREATE TABLE IF NOT EXISTS merchant_ops_config (
    merchant_id              VARCHAR(64)  PRIMARY KEY,
    stocking_type            VARCHAR(16)  NOT NULL DEFAULT 'CAPACITY', -- CAPACITY | SALES
    stockout_threshold_pct   INT          NOT NULL DEFAULT 50,
    tally_mode               VARCHAR(32)  NOT NULL DEFAULT 'INDEPENDENT', -- INDEPENDENT | ONCE
    use_stocking_list        BOOLEAN      NOT NULL DEFAULT TRUE,
    replenish_input_type     VARCHAR(32)  NOT NULL DEFAULT 'ADD_QTY', -- ADD_QTY | AFTER_QTY
    photo_stocktake          BOOLEAN      NOT NULL DEFAULT FALSE,
    photo_replenish          BOOLEAN      NOT NULL DEFAULT FALSE,
    max_inflight_orders      INT          NOT NULL DEFAULT 0,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 商户侧推荐岗位模板
CREATE TABLE IF NOT EXISTS merchant_role_template (
    template_key   VARCHAR(32)  PRIMARY KEY,
    template_name  VARCHAR(64)  NOT NULL,
    description    VARCHAR(256),
    permission_hint TEXT,
    sort_order     INT          NOT NULL DEFAULT 0
);

INSERT INTO merchant_role_template (template_key, template_name, description, permission_hint, sort_order)
VALUES
    ('CS', '客服', '推荐：处理客诉、查看订单与视频', 'merchant:orders:list,merchant:disputes:list', 10),
    ('REPLENISH', '补货员', '推荐：补货/盘点、查看柜机库存', 'merchant:devices:list,merchant:replenishment:list', 20),
    ('WAREHOUSE', '仓库员', '推荐：仓储与备货单', 'merchant:devices:list,merchant:replenishment:list', 30)
ON CONFLICT (template_key) DO NOTHING;

-- 柜机策略锁
ALTER TABLE device_info ADD COLUMN IF NOT EXISTS price_locked BOOLEAN DEFAULT FALSE;
ALTER TABLE device_info ADD COLUMN IF NOT EXISTS sku_edit_forbidden BOOLEAN DEFAULT FALSE;
ALTER TABLE device_info ADD COLUMN IF NOT EXISTS sale_forbidden BOOLEAN DEFAULT FALSE;

-- 设备运维事件（与交易异常分流）
CREATE TABLE IF NOT EXISTS device_ops_event (
    event_id     BIGSERIAL    PRIMARY KEY,
    device_id    VARCHAR(64)  NOT NULL,
    event_type   VARCHAR(32)  NOT NULL, -- OFFLINE | NO_SALES | UNLOCK | FAULT | AISLE_AUDIT | MAINBOARD
    severity     VARCHAR(16)  NOT NULL DEFAULT 'INFO', -- INFO | WARN | CRITICAL
    title        VARCHAR(128) NOT NULL,
    detail       TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_device_ops_event_device_time ON device_ops_event (device_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_device_ops_event_type_time ON device_ops_event (event_type, created_at DESC);

-- 手机验证流水
CREATE TABLE IF NOT EXISTS phone_verify_log (
    log_id       BIGSERIAL    PRIMARY KEY,
    user_id      BIGINT,
    phone        VARCHAR(32)  NOT NULL,
    channel      VARCHAR(32)  NOT NULL DEFAULT 'WECHAT',
    merchant_id  VARCHAR(64),
    verified_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_phone_verify_log_time ON phone_verify_log (verified_at DESC);
CREATE INDEX IF NOT EXISTS idx_phone_verify_phone ON phone_verify_log (phone);

-- 商户进件
CREATE TABLE IF NOT EXISTS merchant_onboarding (
    onboarding_id        BIGSERIAL    PRIMARY KEY,
    merchant_id          VARCHAR(64)  NOT NULL,
    subject_type         VARCHAR(16)  NOT NULL DEFAULT 'ENTERPRISE',
    alipay_reg_status    VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    wechat_payscore_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    onboard_status       VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    external_merchant_no VARCHAR(64),
    remark               VARCHAR(256),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_merchant_onboarding_merchant ON merchant_onboarding (merchant_id);

-- 平台储值（SaaS 费预付，可欠费）
CREATE TABLE IF NOT EXISTS platform_stored_value (
    merchant_id          VARCHAR(64)  PRIMARY KEY,
    balance_cents        BIGINT       NOT NULL DEFAULT 0,
    warn_threshold_cents BIGINT       NOT NULL DEFAULT 0,
    notify_phone         VARCHAR(32),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS platform_stored_value_txn (
    txn_id        BIGSERIAL    PRIMARY KEY,
    merchant_id   VARCHAR(64)  NOT NULL,
    txn_type      VARCHAR(32)  NOT NULL, -- RECHARGE | FEE | ADJUST
    amount_cents  BIGINT       NOT NULL,
    balance_after BIGINT       NOT NULL,
    remark        VARCHAR(256),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_platform_sv_txn_merchant ON platform_stored_value_txn (merchant_id, created_at DESC);

-- 识别算力
CREATE TABLE IF NOT EXISTS recognition_compute_account (
    merchant_id   VARCHAR(64)  PRIMARY KEY,
    remaining     BIGINT       NOT NULL DEFAULT 0,
    cumulative    BIGINT       NOT NULL DEFAULT 0,
    used          BIGINT       NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS recognition_compute_ledger (
    ledger_id     BIGSERIAL    PRIMARY KEY,
    merchant_id   VARCHAR(64)  NOT NULL,
    gained        BIGINT       NOT NULL DEFAULT 0,
    used_delta    BIGINT       NOT NULL DEFAULT 0,
    remaining     BIGINT       NOT NULL DEFAULT 0,
    remark        VARCHAR(256),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 权限菜单
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 460, 402, 'ops:fund:list', '资金账单', 'C', '/admin/fund-bills', 36, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:fund:list');

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 461, 402, 'ops:device-ops:list', '设备运维', 'C', '/admin/device-ops', 37, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:device-ops:list');

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 462, 402, 'ops:sales-report:list', '销售报表', 'C', '/admin/sales-reports', 38, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:sales-report:list');

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 463, 402, 'ops:phone-verify:list', '手机验证', 'C', '/admin/phone-verify', 39, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:phone-verify:list');

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 464, 402, 'ops:commercial-hub:list', '商业化中心', 'C', '/admin/commercial-hub', 40, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:commercial-hub:list');

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 465,
       (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:fund:list' LIMIT 1),
       'ops:fund:export', '导出资金账单', 'F', NULL, 10, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:fund:export');

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 466,
       (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:rbac:assign' LIMIT 1),
       'ops:rbac:assign:device', '货柜范围', 'F', NULL, 30, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:rbac:assign:device');

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT DISTINCT rp.role_id, p_new.permission_id
FROM ops_permission p_gate
JOIN ops_role_permission rp ON rp.permission_id = p_gate.permission_id
JOIN ops_permission p_new ON p_new.perm_code IN (
    'ops:fund:list', 'ops:fund:export', 'ops:device-ops:list',
    'ops:sales-report:list', 'ops:phone-verify:list', 'ops:commercial-hub:list',
    'ops:rbac:assign:device'
)
WHERE p_gate.perm_code IN ('ops:finance:view', 'ops:order:list', 'ops:rbac:assign', 'ops:admin')
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code IN (
    'ops:fund:list', 'ops:fund:export', 'ops:device-ops:list',
    'ops:sales-report:list', 'ops:phone-verify:list', 'ops:commercial-hub:list',
    'ops:rbac:assign:device'
)
ON CONFLICT DO NOTHING;

SELECT setval(
    pg_get_serial_sequence('ops_permission', 'permission_id'),
    GREATEST((SELECT COALESCE(MAX(permission_id), 1) FROM ops_permission), 1)
);
