-- V76-V82: 完整数据库优化汇总

-- V76: 数据库完整性与性能优化
-- 唯一约束
ALTER TABLE sku_catalog ADD CONSTRAINT IF NOT EXISTS uk_sku_name UNIQUE (sku_name);
ALTER TABLE merchant_info ADD CONSTRAINT IF NOT EXISTS uk_merchant_name UNIQUE (merchant_name);
ALTER TABLE device_info ADD CONSTRAINT IF NOT EXISTS uk_device_name UNIQUE (device_name);

-- 外键约束
ALTER TABLE cabinet_order_line 
    ADD CONSTRAINT IF NOT EXISTS fk_order_line_order 
    FOREIGN KEY (order_id) REFERENCES cabinet_order(order_id) ON DELETE CASCADE;

ALTER TABLE shopping_session 
    ADD CONSTRAINT IF NOT EXISTS fk_session_user 
    FOREIGN KEY (user_id) REFERENCES user_info(user_id) ON DELETE CASCADE;

-- 性能索引
CREATE INDEX IF NOT EXISTS idx_order_user_created ON cabinet_order (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_session_device_state_time ON shopping_session (device_id, state, open_time DESC);

-- V77: 多级分账管理
CREATE TABLE IF NOT EXISTS revenue_share_rule (
    rule_id BIGSERIAL PRIMARY KEY,
    rule_name VARCHAR(128) NOT NULL,
    rule_type VARCHAR(32) NOT NULL,
    target_id VARCHAR(64),
    device_scope VARCHAR(32) NOT NULL DEFAULT 'ALL',
    share_percent REAL NOT NULL,
    priority INT NOT NULL DEFAULT 0,
    effective_start DATE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS revenue_share_detail (
    share_id BIGSERIAL PRIMARY KEY,
    order_id VARCHAR(32) NOT NULL,
    rule_type VARCHAR(32) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    share_amount_cents INT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_share_detail_order ON revenue_share_detail (order_id);

-- V78: 加盟商管理
CREATE TABLE IF NOT EXISTS franchise_partner (
    partner_id VARCHAR(64) PRIMARY KEY,
    partner_name VARCHAR(128) NOT NULL,
    partner_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS franchise_device (
    partner_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    PRIMARY KEY (partner_id, device_id)
);

-- V79: 线长管理
CREATE TABLE IF NOT EXISTS line_leader (
    leader_id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    leader_name VARCHAR(64) NOT NULL,
    phone_number VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS line_leader_device (
    leader_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (leader_id, device_id)
);

-- V80: 会员等级与积分
CREATE TABLE IF NOT EXISTS member_level (
    level_id INT PRIMARY KEY,
    level_name VARCHAR(32) NOT NULL,
    min_points INT NOT NULL DEFAULT 0,
    discount_percent REAL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS user_points (
    user_id BIGINT PRIMARY KEY,
    total_points INT NOT NULL DEFAULT 0,
    level_id INT NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- V81: 营销活动
CREATE TABLE IF NOT EXISTS marketing_campaign (
    campaign_id BIGSERIAL PRIMARY KEY,
    campaign_name VARCHAR(128) NOT NULL,
    start_time TIMESTAMPTZ NOT NULL,
    end_time TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS coupon_definition (
    coupon_def_id BIGSERIAL PRIMARY KEY,
    coupon_name VARCHAR(128) NOT NULL,
    coupon_type VARCHAR(32) NOT NULL,
    denomination_cents INT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- V82: 默认数据插入
INSERT INTO member_level (level_id, level_name, min_points, discount_percent) VALUES
    (1, '青铜会员', 0, 0),
    (2, '白银会员', 1000, 5),
    (3, '黄金会员', 5000, 10),
    (4, '铂金会员', 10000, 15),
    (5, '钻石会员', 50000, 20)
ON CONFLICT (level_id) DO NOTHING;
