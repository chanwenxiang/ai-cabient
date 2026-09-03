-- 消费者开门预授权：按会话冻结明细，避免账户级 frozen 与多会话 held 混用错放
CREATE TABLE IF NOT EXISTS consumer_preauth_hold (
    session_id   VARCHAR(64) PRIMARY KEY,
    user_id      BIGINT NOT NULL,
    hold_cents   INT NOT NULL CHECK (hold_cents >= 0),
    status       VARCHAR(16) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_consumer_preauth_hold_user_status
    ON consumer_preauth_hold (user_id, status);

COMMENT ON TABLE consumer_preauth_hold IS '开门预授权会话冻结明细；冲抵/释放只动本会话 hold_cents';
COMMENT ON COLUMN consumer_preauth_hold.status IS 'FROZEN / CAPTURED / RELEASED';

-- 回填仍处于 FROZEN 的会话
INSERT INTO consumer_preauth_hold (session_id, user_id, hold_cents, status, created_at, updated_at)
SELECT s.session_id,
       s.user_id,
       GREATEST(0, COALESCE(s.preauth_cents, 0)),
       'FROZEN',
       COALESCE(s.created_at, NOW()),
       NOW()
FROM shopping_session s
WHERE UPPER(COALESCE(s.preauth_status, '')) = 'FROZEN'
  AND s.user_id IS NOT NULL
  AND COALESCE(s.preauth_cents, 0) > 0
ON CONFLICT (session_id) DO NOTHING;
