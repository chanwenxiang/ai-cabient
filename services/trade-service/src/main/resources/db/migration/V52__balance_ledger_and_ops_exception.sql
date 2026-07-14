ALTER TABLE payment_operation ADD COLUMN IF NOT EXISTS user_id BIGINT;
ALTER TABLE payment_operation ADD COLUMN IF NOT EXISTS balance_before_cents INT;
ALTER TABLE payment_operation ADD COLUMN IF NOT EXISTS balance_after_cents INT;

CREATE INDEX IF NOT EXISTS idx_payment_operation_user
    ON payment_operation (user_id, created_at DESC);

ALTER TABLE cabinet_order ADD COLUMN IF NOT EXISTS payment_operation_id VARCHAR(64);
ALTER TABLE cabinet_order ADD COLUMN IF NOT EXISTS balance_before_cents INT;
ALTER TABLE cabinet_order ADD COLUMN IF NOT EXISTS balance_after_cents INT;

CREATE TABLE IF NOT EXISTS ops_exception (
    exception_id      VARCHAR(32) PRIMARY KEY,
    exception_type    VARCHAR(32) NOT NULL,
    severity          VARCHAR(16) NOT NULL,
    status            VARCHAR(16) NOT NULL,
    device_id         VARCHAR(64),
    session_id        VARCHAR(32),
    order_id          VARCHAR(32),
    user_id           BIGINT,
    title             VARCHAR(128) NOT NULL,
    detail            VARCHAR(1000),
    assignee_user_id  BIGINT,
    resolution        VARCHAR(1000),
    dedup_key         VARCHAR(160) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at       TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_ops_exception_open_dedup
    ON ops_exception (dedup_key)
    WHERE status IN ('OPEN', 'PROCESSING');
CREATE INDEX IF NOT EXISTS idx_ops_exception_status_created
    ON ops_exception (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ops_exception_device
    ON ops_exception (device_id, created_at DESC);
