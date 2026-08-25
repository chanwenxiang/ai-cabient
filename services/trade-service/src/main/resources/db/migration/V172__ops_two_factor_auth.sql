-- V100: 运营账号双因子认证（TOTP）
ALTER TABLE user_info ADD COLUMN IF NOT EXISTS totp_secret VARCHAR(128);
ALTER TABLE user_info ADD COLUMN IF NOT EXISTS totp_enabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS ops_2fa_recovery_code (
    user_id    BIGINT NOT NULL,
    code_hash  CHAR(64) NOT NULL,
    used       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, code_hash)
);
