-- V79: 会员运营管理

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
CREATE INDEX IF NOT EXISTS idx_member_invited_by ON member (invited_by);

COMMENT ON TABLE member IS '会员表';

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
CREATE INDEX IF NOT EXISTS idx_member_points_expire ON member_points_log (expire_at);

COMMENT ON TABLE member_points_log IS '会员积分日志表';

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
CREATE INDEX IF NOT EXISTS idx_member_level_status ON member_level_rule (status, sortorder);

COMMENT ON TABLE member_level_rule IS '会员等级规则表';

-- 插入默认等级规则
INSERT INTO member_level_rule (level_code, level_name, min_spent, max_spent, min_points, points_rate, sortorder, status) VALUES
('NORMAL', '普通会员', 0, 1000, 0, 1.00, 1, 'ACTIVE'),
('SILVER', '银卡会员', 1000, 5000, 100, 1.20, 2, 'ACTIVE'),
('GOLD', '金卡会员', 5000, 10000, 500, 1.50, 3, 'ACTIVE'),
('PLATINUM', '白金会员', 10000, NULL, 1000, 2.00, 4, 'ACTIVE')
ON CONFLICT (level_code) DO NOTHING;
