-- V83: 游戏化运营

CREATE TABLE IF NOT EXISTS user_checkin (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    checkin_date DATE NOT NULL,
    consecutive_days INT NOT NULL DEFAULT 1,
    total_days INT NOT NULL DEFAULT 1,
    reward_points INT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_checkin_user ON user_checkin (user_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_user_checkin_unique ON user_checkin (user_id, checkin_date);

COMMENT ON TABLE user_checkin IS '用户签到表';

CREATE TABLE IF NOT EXISTS achievement (
    achievement_id BIGSERIAL PRIMARY KEY,
    achievement_code VARCHAR(64) NOT NULL UNIQUE,
    achievement_name VARCHAR(100) NOT NULL,
    description VARCHAR(200),
    category VARCHAR(32) NOT NULL,
    icon_url VARCHAR(64),
    required_progress INT NOT NULL,
    reward_points INT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_achievement_category ON achievement (category);

COMMENT ON TABLE achievement IS '成就表';

CREATE TABLE IF NOT EXISTS user_achievement (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    achievement_id BIGINT NOT NULL,
    current_progress INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_user_achievement_user ON user_achievement (user_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_user_achievement_unique ON user_achievement (user_id, achievement_id);

COMMENT ON TABLE user_achievement IS '用户成就表';

CREATE TABLE IF NOT EXISTS game_task (
    task_id BIGSERIAL PRIMARY KEY,
    task_code VARCHAR(64) NOT NULL UNIQUE,
    task_name VARCHAR(100) NOT NULL,
    description VARCHAR(200),
    task_type VARCHAR(32) NOT NULL,
    required_progress INT NOT NULL,
    reward_points INT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_game_task_type ON game_task (task_type);

COMMENT ON TABLE game_task IS '游戏任务表';

CREATE TABLE IF NOT EXISTS user_game_task (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    current_progress INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    claimed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_user_game_task_user ON user_game_task (user_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_user_game_task_unique ON user_game_task (user_id, task_id);

COMMENT ON TABLE user_game_task IS '用户任务表';
