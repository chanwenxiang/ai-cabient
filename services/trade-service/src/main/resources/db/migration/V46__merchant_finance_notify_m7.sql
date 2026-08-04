-- M7：财务子角色、微信订阅偏好、演示财务账号

INSERT INTO ops_role (role_id, role_key, role_name, remark) VALUES
    (8, 'merchant_finance', '商户财务', '结算对账与经营分析，只读设备/订单')
ON CONFLICT (role_key) DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 8, permission_id FROM ops_permission WHERE perm_code IN (
    'merchant:portal:access',
    'merchant:devices:list',
    'merchant:devices:detail',
    'merchant:orders:list',
    'merchant:alerts:view',
    'merchant:trend:view',
    'merchant:splits:list',
    'merchant:settlements:view',
    'merchant:settlements:export',
    'merchant:analytics:view',
    'merchant:reports:view',
    'merchant:reports:export',
    'merchant:temp:history',
    'merchant:disputes:list'
)
ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS merchant_subscribe_pref (
    user_id     BIGINT       NOT NULL REFERENCES user_info(user_id),
    alert_type  VARCHAR(32)  NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, alert_type)
);

CREATE TABLE IF NOT EXISTS merchant_notify_log (
    log_id      BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    digest      VARCHAR(64)  NOT NULL,
    payload     TEXT,
    sent_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_merchant_notify_log_user_sent
    ON merchant_notify_log (user_id, sent_at DESC);

-- 演示财务账号：13800138004 / 123456，绑定 MCH-DEFAULT
INSERT INTO user_info (user_id, phone_number, name, verified)
VALUES (100000005, '13800138004', '商户财务', TRUE)
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO user_account (user_id, balance_cents)
VALUES (100000005, 0)
ON CONFLICT (user_id) DO NOTHING;

UPDATE user_info
SET password_hash = '$2b$10$wtX2jX5K5h0IuUQxT2BAqOMT19ngpMGUDR76A3MdQcGnoCZzM6oFC'
WHERE user_id = 100000005
  AND (password_hash IS NULL OR password_hash = '');

INSERT INTO ops_user_role (user_id, role_id) VALUES (100000005, 8)
ON CONFLICT DO NOTHING;

INSERT INTO ops_user_merchant (user_id, merchant_id) VALUES (100000005, 'MCH-DEFAULT')
ON CONFLICT DO NOTHING;
