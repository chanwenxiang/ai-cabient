-- V82: 社交运营管理

CREATE TABLE IF NOT EXISTS share_reward (
    reward_id BIGSERIAL PRIMARY KEY,
    sharer_id BIGINT NOT NULL,
    invitee_id BIGINT,
    order_id VARCHAR(32),
    reward_type VARCHAR(16) NOT NULL,
    reward_amount DECIMAL(10,2),
    reward_points INT,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    claimed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_share_reward_sharer ON share_reward (sharer_id);
CREATE INDEX IF NOT EXISTS idx_share_reward_invitee ON share_reward (invitee_id);

COMMENT ON TABLE share_reward IS '分享奖励表';

CREATE TABLE IF NOT EXISTS group_buy (
    group_buy_id BIGSERIAL PRIMARY KEY,
    title VARCHAR(100) NOT NULL,
    sku_id BIGINT NOT NULL,
    original_price DECIMAL(10,2) NOT NULL,
    group_price DECIMAL(10,2) NOT NULL,
    min_participants INT NOT NULL,
    max_participants INT NOT NULL,
    current_participants INT NOT NULL DEFAULT 0,
    start_time TIMESTAMPTZ NOT NULL,
    end_time TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_group_buy_status ON group_buy (status);

COMMENT ON TABLE group_buy IS '拼团活动表';

CREATE TABLE IF NOT EXISTS group_buy_participant (
    id BIGSERIAL PRIMARY KEY,
    group_buy_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    order_id VARCHAR(32),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_group_buy_participant ON group_buy_participant (group_buy_id);

COMMENT ON TABLE group_buy_participant IS '拼团参与表';

CREATE TABLE IF NOT EXISTS red_packet (
    packet_id BIGSERIAL PRIMARY KEY,
    sender_id BIGINT NOT NULL,
    packet_code VARCHAR(32) NOT NULL UNIQUE,
    total_amount DECIMAL(10,2) NOT NULL,
    total_count INT NOT NULL,
    claimed_count INT NOT NULL DEFAULT 0,
    min_amount DECIMAL(10,2) NOT NULL,
    max_amount DECIMAL(10,2) NOT NULL,
    message VARCHAR(200),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expired_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_red_packet_sender ON red_packet (sender_id);
CREATE INDEX IF NOT EXISTS idx_red_packet_code ON red_packet (packet_code);

COMMENT ON TABLE red_packet IS '红包表';

CREATE TABLE IF NOT EXISTS red_packet_claim (
    id BIGSERIAL PRIMARY KEY,
    packet_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    claimed_amount DECIMAL(10,2) NOT NULL,
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_red_packet_claim_packet ON red_packet_claim (packet_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_red_packet_claim_unique ON red_packet_claim (packet_id, user_id);

COMMENT ON TABLE red_packet_claim IS '红包领取记录表';
