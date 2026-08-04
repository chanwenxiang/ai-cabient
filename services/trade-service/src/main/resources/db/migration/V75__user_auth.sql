-- V75: 用户认证与实名认证优化

CREATE TABLE IF NOT EXISTS wechat_binding (
    binding_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES user_info(user_id),
    open_id VARCHAR(64) NOT NULL UNIQUE,
    union_id VARCHAR(64),
    session_key VARCHAR(128),
    nickname VARCHAR(128),
    avatar_url VARCHAR(512),
    gender INT,
    city VARCHAR(64),
    province VARCHAR(64),
    country VARCHAR(64),
    bind_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_login_time TIMESTAMPTZ,
    login_count INT DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_wechat_binding_user ON wechat_binding (user_id);
CREATE INDEX IF NOT EXISTS idx_wechat_binding_openid ON wechat_binding (open_id);

COMMENT ON TABLE wechat_binding IS '微信登录绑定表';

CREATE TABLE IF NOT EXISTS user_realname_auth (
    auth_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES user_info(user_id),
    real_name VARCHAR(64) NOT NULL,
    id_card_number VARCHAR(32) NOT NULL,
    id_card_type VARCHAR(16) DEFAULT 'ID_CARD',
    auth_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    auth_method VARCHAR(32),
    auth_time TIMESTAMPTZ,
    expire_time TIMESTAMPTZ,
    reject_reason VARCHAR(256),
    verified_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id)
);

CREATE INDEX IF NOT EXISTS idx_realname_auth_status ON user_realname_auth (auth_status, created_at);

COMMENT ON TABLE user_realname_auth IS '用户实名认证表';

CREATE TABLE IF NOT EXISTS user_login_log (
    log_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    login_type VARCHAR(16) NOT NULL,
    login_ip VARCHAR(45),
    login_device VARCHAR(128),
    login_location VARCHAR(128),
    login_result VARCHAR(16) NOT NULL,
    fail_reason VARCHAR(256),
    login_time TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_login_log_user ON user_login_log (user_id, login_time DESC);
