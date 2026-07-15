-- Phase 7: 运营操作审计日志

CREATE TABLE IF NOT EXISTS admin_audit_log (
    log_id        BIGSERIAL    PRIMARY KEY,
    operator_id   BIGINT       NOT NULL,
    action        VARCHAR(64)  NOT NULL,
    target_type   VARCHAR(32),
    target_id     VARCHAR(64),
    detail        VARCHAR(512),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_admin_audit_created ON admin_audit_log (created_at DESC);
CREATE INDEX idx_admin_audit_operator ON admin_audit_log (operator_id);
