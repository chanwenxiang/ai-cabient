-- SMS 验证码持久化（替代内存 Map；dev/staging/prod 统一走库）

CREATE TABLE IF NOT EXISTS sms_verification_code (
    id            BIGSERIAL    PRIMARY KEY,
    phone_number  VARCHAR(32)  NOT NULL,
    code          VARCHAR(8)   NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL,
    used_at       TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sms_code_phone_created
    ON sms_verification_code (phone_number, created_at DESC);
