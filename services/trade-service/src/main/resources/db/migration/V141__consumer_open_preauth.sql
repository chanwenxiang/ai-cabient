-- 消费者开门预授权：余额冻结 + 会话预授权状态
ALTER TABLE user_account
    ADD COLUMN IF NOT EXISTS frozen_cents INT NOT NULL DEFAULT 0;

ALTER TABLE shopping_session
    ADD COLUMN IF NOT EXISTS preauth_cents INT NOT NULL DEFAULT 0;

ALTER TABLE shopping_session
    ADD COLUMN IF NOT EXISTS preauth_status VARCHAR(16) NOT NULL DEFAULT 'NONE';

COMMENT ON COLUMN user_account.frozen_cents IS '已冻结分（开门预授权等），可用余额=balance_cents-frozen_cents';
COMMENT ON COLUMN shopping_session.preauth_cents IS '本会话预授权冻结金额（分）';
COMMENT ON COLUMN shopping_session.preauth_status IS 'NONE/FROZEN/CAPTURED/RELEASED';
