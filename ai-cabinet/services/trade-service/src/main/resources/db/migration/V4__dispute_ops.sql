-- Phase 3: 争议工单、运营会话标记

CREATE TABLE IF NOT EXISTS dispute_ticket (
    ticket_id     VARCHAR(32)  PRIMARY KEY,
    session_id    VARCHAR(32)  NOT NULL REFERENCES shopping_session(session_id),
    reason        VARCHAR(256),
    status        VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    resolved_at   TIMESTAMPTZ
);

CREATE INDEX idx_dispute_session ON dispute_ticket (session_id);

-- 测试用户绑定 mock openId（真实环境由 wx.login 换取）
UPDATE user_info SET wx_open_id = 'mock_openid_10001' WHERE user_id = 10001 AND wx_open_id IS NULL;

-- 运营测试账号
INSERT INTO user_info (user_id, phone_number, name, verified)
VALUES (100000001, '13900000001', '运营测试', TRUE)
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO user_account (user_id, balance_cents)
VALUES (100000001, 0)
ON CONFLICT (user_id) DO NOTHING;
