-- V163: 增长运营模块（会员积分闭环 / 消息触达 / 选品淘汰诊断）
-- 说明：V136 曾删除积分遗留表，此处重建积分表与列；再补通知与选品评审表。

-- 1) member 积分列保障（兼容 V79/V99 之前已建表的库）
ALTER TABLE member
    ADD COLUMN IF NOT EXISTS total_points INT NOT NULL DEFAULT 0;
ALTER TABLE member
    ADD COLUMN IF NOT EXISTS available_points INT NOT NULL DEFAULT 0;
ALTER TABLE member
    ADD COLUMN IF NOT EXISTS used_points INT NOT NULL DEFAULT 0;
ALTER TABLE member
    ADD COLUMN IF NOT EXISTS expired_points INT NOT NULL DEFAULT 0;
ALTER TABLE member
    ADD COLUMN IF NOT EXISTS invite_code VARCHAR(64);
ALTER TABLE member
    ADD COLUMN IF NOT EXISTS invited_by BIGINT;

CREATE INDEX IF NOT EXISTS idx_member_user ON member (user_id);
CREATE INDEX IF NOT EXISTS idx_member_level ON member (member_level);

-- 2) member_level_rule 积分倍率列保障
ALTER TABLE member_level_rule
    ADD COLUMN IF NOT EXISTS min_points INT NOT NULL DEFAULT 0;
ALTER TABLE member_level_rule
    ADD COLUMN IF NOT EXISTS max_points INT;
ALTER TABLE member_level_rule
    ADD COLUMN IF NOT EXISTS points_rate DECIMAL(5,2) NOT NULL DEFAULT 1.00;

-- 恢复默认等级倍率与积分门槛（V136 曾删除这些列，行仍存在）
UPDATE member_level_rule SET points_rate = 1.00, min_points = 0,   max_points = 100   WHERE level_code = 'NORMAL'   AND points_rate = 1.00 AND min_points = 0;
UPDATE member_level_rule SET points_rate = 1.20, min_points = 100, max_points = 500   WHERE level_code = 'SILVER'   AND points_rate = 1.00 AND min_points = 0;
UPDATE member_level_rule SET points_rate = 1.50, min_points = 500, max_points = 1000  WHERE level_code = 'GOLD'     AND points_rate = 1.00 AND min_points = 0;
UPDATE member_level_rule SET points_rate = 2.00, min_points = 1000, max_points = NULL WHERE level_code = 'PLATINUM' AND points_rate = 1.00 AND min_points = 0;

-- 3) 积分日志表（V136 曾删除，重建）
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

-- 4) 积分兑换商品（V136 曾删除，重建）
CREATE TABLE IF NOT EXISTS points_redeem_item (
    item_id BIGSERIAL PRIMARY KEY,
    title VARCHAR(64) NOT NULL,
    subtitle VARCHAR(128),
    cover_emoji VARCHAR(16) NOT NULL DEFAULT '馃巵',
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

-- 积分兑换目录依赖的优惠券定义（按名称幂等）
INSERT INTO coupon_definition (
    coupon_name, coupon_type, denomination_cents, min_spend_cents, discount_percent,
    validity_days, max_issue_count, issued_count, device_scope, status, description, created_at
)
SELECT v.coupon_name, v.coupon_type, v.denomination_cents, v.min_spend_cents, NULL,
       v.validity_days, v.max_issue_count, 0, 'ALL', 'ACTIVE', v.description, NOW()
FROM (VALUES
    ('满 20 减 5 券',  'AMOUNT_OFF', 500,  0,    30, 100000, '无门槛，积分兑换'),
    ('满 50 减 10 券', 'AMOUNT_OFF', 1000, 2000, 30, 100000, '满 50 可用，积分兑换'),
    ('满 100 减 20 券','AMOUNT_OFF', 2000, 5000, 30, 100000, '满 100 可用，积分兑换')
) AS v(coupon_name, coupon_type, denomination_cents, min_spend_cents, validity_days, max_issue_count, description)
WHERE NOT EXISTS (
    SELECT 1 FROM coupon_definition d WHERE d.coupon_name = v.coupon_name
);

-- 默认积分兑换目录（引用现有优惠券定义，按名称去重）
INSERT INTO points_redeem_item (title, subtitle, cover_emoji, points_cost, coupon_def_id, stock_total, redeemed_count, sort_order, status)
SELECT v.title, v.subtitle, v.cover_emoji, v.points_cost, d.coupon_def_id, v.stock_total, 0, v.sort_order, 'ACTIVE'
FROM (VALUES
    ('满 20 减 5 券', '无门槛 · 热门兑换', '🎁', 100, '满 20 减 5 券', 2000, 1),
    ('满 50 减 10 券', '满 50 可用 · 日常优惠', '🧧', 300, '满 50 减 10 券', 1000, 2),
    ('满 100 减 20 券', '满 100 可用 · 大额回馈', '🎉', 800, '满 100 减 20 券', 500, 3)
) AS v(title, subtitle, cover_emoji, points_cost, coupon_name, stock_total, sort_order)
JOIN coupon_definition d ON d.coupon_name = v.coupon_name
WHERE NOT EXISTS (
    SELECT 1 FROM points_redeem_item i WHERE i.title = v.title
);

-- 5) 通知模板
CREATE TABLE IF NOT EXISTS notification_template (
    template_id BIGSERIAL PRIMARY KEY,
    template_code VARCHAR(64) NOT NULL UNIQUE,
    template_name VARCHAR(100) NOT NULL,
    channel VARCHAR(16) NOT NULL DEFAULT 'IN_APP',
    title_template VARCHAR(160) NOT NULL,
    body_template VARCHAR(512) NOT NULL,
    audience VARCHAR(16) NOT NULL DEFAULT 'CONSUMER',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ
);

-- 通知渠道列（后续 V165-167 的模板数据依赖；V168 保留幂等兜底）
ALTER TABLE notification_template
    ADD COLUMN IF NOT EXISTS channels VARCHAR(64) NOT NULL DEFAULT 'IN_APP';

INSERT INTO notification_template
    (template_code, template_name, channel, title_template, body_template, audience)
VALUES
    ('order_paid', '订单支付成功', 'IN_APP',
     '订单支付成功',
     '您的订单 {orderId} 已支付 {amount} 元，感谢惠顾。',
     'CONSUMER'),
    ('recharge_success', '充值到账', 'IN_APP',
     '充值成功',
     '账户已到账 {amount} 元，可在「我的」查看余额。',
     'CONSUMER'),
    ('coupon_expiring', '优惠券即将过期', 'IN_APP',
     '优惠券即将过期',
     '您的「{couponName}」将于 {expireAt} 过期，请尽快使用。',
     'CONSUMER'),
    ('dispute_resolved', '售后处理完成', 'IN_APP',
     '售后处理完成',
     '您的申诉 {disputeNo} 已处理：{result}。',
     'CONSUMER'),
    ('replenishment_assigned', '新补货任务', 'IN_APP',
     '新补货任务 #{taskId}',
     '任务已分配，请于 {time} 前完成，涉及 {deviceName}。',
     'MERCHANT'),
    ('merchant_settlement', '结算到账', 'IN_APP',
     '结算已到账',
     '本期结算 {amount} 元已入账，请注意查收。',
     'MERCHANT')
ON CONFLICT (template_code) DO NOTHING;

-- 6) 通知日志
CREATE TABLE IF NOT EXISTS notification_log (
    id BIGSERIAL PRIMARY KEY,
    template_code VARCHAR(64) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    audience VARCHAR(16) NOT NULL,
    user_id BIGINT,
    merchant_id VARCHAR(32),
    title VARCHAR(160) NOT NULL,
    body TEXT NOT NULL,
    biz_type VARCHAR(32),
    biz_id VARCHAR(64),
    status VARCHAR(16) NOT NULL DEFAULT 'SENT',
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_notification_log_user ON notification_log (audience, user_id, read_at);
CREATE INDEX IF NOT EXISTS idx_notification_log_merchant ON notification_log (audience, merchant_id, read_at);
CREATE INDEX IF NOT EXISTS idx_notification_log_created ON notification_log (created_at DESC);

-- 7) 选品淘汰评审
CREATE TABLE IF NOT EXISTS sku_delist_review (
    id BIGSERIAL PRIMARY KEY,
    sku_id VARCHAR(32) NOT NULL,
    review_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    performance_level VARCHAR(16),
    sales_qty INT NOT NULL DEFAULT 0,
    revenue_cents BIGINT NOT NULL DEFAULT 0,
    stock_days INT,
    action_type VARCHAR(16),
    reason VARCHAR(256),
    replace_sku_id VARCHAR(32),
    reviewed_by BIGINT,
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ,
    UNIQUE (sku_id)
);
CREATE INDEX IF NOT EXISTS idx_sku_delist_review_status ON sku_delist_review (review_status);

-- 8) 运营后台权限：积分兑换 / 选品诊断 / 用户分析 / 消息记录
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
VALUES
    (600, 402, 'ops:points:list', '积分兑换管理', 'C', '/points-redeem', 110, 'ACTIVE'),
    (601, 600, 'ops:points:edit', '积分兑换编辑', 'F', NULL, 1, 'ACTIVE'),
    (602, 402, 'ops:sku-review:list', '选品诊断', 'C', '/sku-review', 120, 'ACTIVE'),
    (603, 602, 'ops:sku-review:edit', '选品诊断处理', 'F', NULL, 1, 'ACTIVE'),
    (604, 400, 'ops:user-analysis:view', '用户分析', 'C', '/user-analysis', 50, 'ACTIVE'),
    (605, 402, 'ops:notify:list', '消息记录', 'C', '/notifications', 130, 'ACTIVE')
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code IN ('ops:points:list', 'ops:points:edit', 'ops:sku-review:list', 'ops:sku-review:edit',
                    'ops:user-analysis:view', 'ops:notify:list')
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 2, permission_id FROM ops_permission
WHERE perm_code IN ('ops:points:list', 'ops:sku-review:list', 'ops:user-analysis:view', 'ops:notify:list')
ON CONFLICT DO NOTHING;

SELECT setval(
    pg_get_serial_sequence('ops_permission', 'permission_id'),
    GREATEST((SELECT COALESCE(MAX(permission_id), 1) FROM ops_permission), 1)
);
