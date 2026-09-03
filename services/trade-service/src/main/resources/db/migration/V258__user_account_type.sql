-- 显式账号类型，替代仅靠 userId >= 1e8 判断运营身份
ALTER TABLE user_info
    ADD COLUMN IF NOT EXISTS account_type VARCHAR(16);

UPDATE user_info
SET account_type = CASE
    WHEN user_id >= 100000000 THEN 'OPERATOR'
    ELSE 'CONSUMER'
END
WHERE account_type IS NULL OR account_type = '';

ALTER TABLE user_info
    ALTER COLUMN account_type SET DEFAULT 'CONSUMER';

ALTER TABLE user_info
    ALTER COLUMN account_type SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_user_info_account_type ON user_info (account_type);

COMMENT ON COLUMN user_info.account_type IS 'CONSUMER / OPERATOR；鉴权与 JWT claim 使用，不再仅依赖 userId 号段';
