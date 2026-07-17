-- V99: 消费者会员中心 / 积分兑换 / 营销活动演示数据
-- 兼容历史库：部分环境 V79 已记录但未真正建表

CREATE TABLE IF NOT EXISTS member (
    member_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    member_level VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    total_points INT NOT NULL DEFAULT 0,
    available_points INT NOT NULL DEFAULT 0,
    used_points INT NOT NULL DEFAULT 0,
    expired_points INT NOT NULL DEFAULT 0,
    total_spent DECIMAL(12,2) NOT NULL DEFAULT 0,
    order_count INT NOT NULL DEFAULT 0,
    invite_code VARCHAR(64),
    invited_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ,
    level_upgrade_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_member_user ON member (user_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_member_invite_code ON member (invite_code) WHERE invite_code IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_member_level ON member (member_level);

CREATE TABLE IF NOT EXISTS member_points_log (
    id BIGSERIAL PRIMARY KEY,
    member_id BIGINT NOT NULL,
    points INT NOT NULL,
    points_type VARCHAR(16) NOT NULL,
    source_type VARCHAR(64),
    source_id VARCHAR(64),
    description VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expire_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_member_points_member ON member_points_log (member_id);
CREATE INDEX IF NOT EXISTS idx_member_points_type ON member_points_log (member_id, points_type);

CREATE TABLE IF NOT EXISTS member_level_rule (
    id BIGSERIAL PRIMARY KEY,
    level_code VARCHAR(16) NOT NULL,
    level_name VARCHAR(32) NOT NULL,
    min_spent DECIMAL(12,2),
    max_spent DECIMAL(12,2),
    min_points INT NOT NULL DEFAULT 0,
    max_points INT,
    points_rate DECIMAL(5,2),
    sortorder INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_member_level_code ON member_level_rule (level_code);

INSERT INTO member_level_rule (level_code, level_name, min_spent, max_spent, min_points, points_rate, sortorder, status) VALUES
('NORMAL', '普通会员', 0, 1000, 0, 1.00, 1, 'ACTIVE'),
('SILVER', '银卡会员', 1000, 5000, 100, 1.20, 2, 'ACTIVE'),
('GOLD', '金卡会员', 5000, 10000, 500, 1.50, 3, 'ACTIVE'),
('PLATINUM', '白金会员', 10000, NULL, 1000, 2.00, 4, 'ACTIVE')
ON CONFLICT (level_code) DO NOTHING;

CREATE TABLE IF NOT EXISTS points_redeem_item (
    item_id BIGSERIAL PRIMARY KEY,
    title VARCHAR(64) NOT NULL,
    subtitle VARCHAR(128),
    cover_emoji VARCHAR(16) NOT NULL DEFAULT '🎁',
    points_cost INT NOT NULL,
    coupon_def_id BIGINT NOT NULL,
    stock_total INT NOT NULL DEFAULT 0,
    redeemed_count INT NOT NULL DEFAULT 0,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_points_redeem_status ON points_redeem_item (status, sort_order);

COMMENT ON TABLE points_redeem_item IS '积分兑换商品（兑换为优惠券）';

-- 演示优惠券定义（幂等：按名称去重）
INSERT INTO coupon_definition (
    coupon_name, coupon_type, denomination_cents, min_spend_cents, discount_percent,
    validity_days, max_issue_count, issued_count, device_scope, status, description, created_at
)
SELECT v.coupon_name, v.coupon_type, v.denomination_cents, v.min_spend_cents, NULL,
       v.validity_days, v.max_issue_count, 0, 'ALL', 'ACTIVE', v.description, NOW()
FROM (VALUES
    ('新人立减 ¥2', 'AMOUNT_OFF', 200, 0, 30, 5000, '无门槛，开门购物即可用'),
    ('满减券 ¥5', 'AMOUNT_OFF', 500, 2000, 30, 5000, '满 ¥20 可用'),
    ('满减券 ¥10', 'AMOUNT_OFF', 1000, 5000, 45, 3000, '满 ¥50 可用')
) AS v(coupon_name, coupon_type, denomination_cents, min_spend_cents, validity_days, max_issue_count, description)
WHERE NOT EXISTS (
    SELECT 1 FROM coupon_definition d WHERE d.coupon_name = v.coupon_name
);

-- 积分兑换目录
INSERT INTO points_redeem_item (title, subtitle, cover_emoji, points_cost, coupon_def_id, stock_total, redeemed_count, sort_order, status, created_at)
SELECT v.title, v.subtitle, v.cover_emoji, v.points_cost, d.coupon_def_id, v.stock_total, 0, v.sort_order, 'ACTIVE', NOW()
FROM (VALUES
    ('兑 ¥2 立减券', '无门槛 · 热门兑换', '🎫', 100, '新人立减 ¥2', 2000, 1),
    ('兑 ¥5 满减券', '满20可用 · 日常优选', '🥤', 300, '满减券 ¥5', 1000, 2),
    ('兑 ¥10 满减券', '满50可用 · 大额回馈', '🎁', 800, '满减券 ¥10', 500, 3)
) AS v(title, subtitle, cover_emoji, points_cost, coupon_name, stock_total, sort_order)
JOIN coupon_definition d ON d.coupon_name = v.coupon_name
WHERE NOT EXISTS (
    SELECT 1 FROM points_redeem_item i WHERE i.title = v.title
);

-- 演示营销活动
INSERT INTO promotion_activity (
    activity_name, activity_type, status, start_time, end_time,
    budget_cents, used_cents, user_limit, device_scope, rule_config, description, created_at
)
SELECT v.activity_name, v.activity_type, 'ACTIVE', NOW() - INTERVAL '1 day', NOW() + INTERVAL '30 day',
       v.budget_cents, 0, 1, 'ALL', '{}'::jsonb, v.description, NOW()
FROM (VALUES
    ('夏日冰饮满减周', 'DISCOUNT', 5000000, '指定冰饮满20减5，积分双倍'),
    ('新客开门礼', 'NEW_USER', 2000000, '首次开门成功领 ¥2 券'),
    ('积分兑好礼', 'POINTS', 1000000, '100 积分起兑优惠券，每周上新')
) AS v(activity_name, activity_type, budget_cents, description)
WHERE NOT EXISTS (
    SELECT 1 FROM promotion_activity p WHERE p.activity_name = v.activity_name
);

-- 确保演示消费者有会员档案与积分
INSERT INTO member (
    user_id, member_level, total_points, available_points, used_points, expired_points,
    total_spent, order_count, invite_code, created_at
)
SELECT 10001, 'SILVER', 680, 520, 160, 0, 1280.00, 12, 'DEMO1001', NOW()
WHERE EXISTS (SELECT 1 FROM user_info WHERE user_id = 10001)
  AND NOT EXISTS (SELECT 1 FROM member WHERE user_id = 10001);

INSERT INTO member_points_log (member_id, points, points_type, source_type, source_id, description, created_at, expire_at)
SELECT m.member_id, 200, 'EARN', 'WELCOME', 'demo-welcome', '新会员见面礼', NOW() - INTERVAL '10 day', NOW() + INTERVAL '355 day'
FROM member m
WHERE m.user_id = 10001
  AND NOT EXISTS (
      SELECT 1 FROM member_points_log l
      WHERE l.member_id = m.member_id AND l.source_id = 'demo-welcome'
  );

INSERT INTO member_points_log (member_id, points, points_type, source_type, source_id, description, created_at, expire_at)
SELECT m.member_id, 320, 'EARN', 'ORDER', 'demo-order-1', '购物返积分', NOW() - INTERVAL '3 day', NOW() + INTERVAL '362 day'
FROM member m
WHERE m.user_id = 10001
  AND NOT EXISTS (
      SELECT 1 FROM member_points_log l
      WHERE l.member_id = m.member_id AND l.source_id = 'demo-order-1'
  );

INSERT INTO member_points_log (member_id, points, points_type, source_type, source_id, description, created_at)
SELECT m.member_id, -160, 'USE', 'REDEEM', 'demo-redeem-1', '兑换满减券', NOW() - INTERVAL '2 day'
FROM member m
WHERE m.user_id = 10001
  AND NOT EXISTS (
      SELECT 1 FROM member_points_log l
      WHERE l.member_id = m.member_id AND l.source_id = 'demo-redeem-1'
  );

-- 给演示用户发一张可用券
INSERT INTO user_coupon (user_id, coupon_def_id, coupon_code, status, expire_at, received_at)
SELECT 10001, d.coupon_def_id, 'DEMO2OFF001', 'UNUSED', NOW() + INTERVAL '20 day', NOW()
FROM coupon_definition d
WHERE d.coupon_name = '新人立减 ¥2'
  AND EXISTS (SELECT 1 FROM user_info WHERE user_id = 10001)
  AND NOT EXISTS (
      SELECT 1 FROM user_coupon uc WHERE uc.user_id = 10001 AND uc.coupon_code = 'DEMO2OFF001'
  );
