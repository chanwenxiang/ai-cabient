-- shopping_session: 购物会话（替代旧 m8_door_current_status + 销售链路）

CREATE TABLE IF NOT EXISTS shopping_session (
    session_id          VARCHAR(32)  PRIMARY KEY,
    user_id             BIGINT       NOT NULL,
    device_id           VARCHAR(64)  NOT NULL,
    state               VARCHAR(32)  NOT NULL,
    open_time           TIMESTAMPTZ,
    close_time          TIMESTAMPTZ,
    order_id            VARCHAR(32),
    recognition_task_id VARCHAR(64),
    video_uri           VARCHAR(512),
    idempotency_key     VARCHAR(64)  UNIQUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_session_device ON shopping_session (device_id);
CREATE INDEX idx_session_user ON shopping_session (user_id);
CREATE INDEX idx_session_state ON shopping_session (state);

-- device_info: 设备注册（参考旧 ego_machine_base_info）

CREATE TABLE IF NOT EXISTS device_info (
    device_id         VARCHAR(64)  PRIMARY KEY,
    device_name       VARCHAR(128),
    device_type       VARCHAR(32)  NOT NULL DEFAULT 'AI_CABINET_V1',
    capabilities      JSONB,
    firmware_version  VARCHAR(32),
    online_status     VARCHAR(16)  NOT NULL DEFAULT 'OFFLINE',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- recognition_result: AI 识别结果

CREATE TABLE IF NOT EXISTS recognition_result (
    task_id             VARCHAR(64)  PRIMARY KEY,
    session_id          VARCHAR(32)  NOT NULL REFERENCES shopping_session(session_id),
    items               JSONB        NOT NULL,
    overall_confidence  REAL,
    fusion_mode         VARCHAR(16)  NOT NULL DEFAULT 'VISION',
    model_version       VARCHAR(32),
    need_review         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_recognition_session ON recognition_result (session_id);
