-- S1 设备凭据落地：每设备独立 MQTT 凭据（替代共享 aicabinet-device 账号）
-- MIGRATION_KIND: schema
-- 设计：docs/S1_DEVICE_CREDENTIAL_DESIGN.md
-- 流程：运营签发（本表落 hash + 明文一次性展示）→ 生成器读本表产出 EMQX bootstrap CSV
--       → EMQX 内置数据库认证（bootstrap_type=plain，导入后 EMQX 侧哈希落库）。
-- 注意：mqtt_secret 列为敏感明文（生成 bootstrap CSV 必需），与 auth-bootstrap.production.csv
--       同敏感级；该 CSV 已 gitignore，本表禁止出现在任何导出/日志里。

CREATE TABLE IF NOT EXISTS device_mqtt_credential (
    device_id      character varying(32) PRIMARY KEY,
    mqtt_username  character varying(64) NOT NULL,
    mqtt_secret    character varying(128) NOT NULL,
    secret_sha256  character varying(64) NOT NULL,
    status         character varying(16) NOT NULL DEFAULT 'ACTIVE',
    issued_by      bigint,
    issued_at      timestamp with time zone NOT NULL DEFAULT NOW(),
    rotated_at     timestamp with time zone,
    revoked_at     timestamp with time zone,
    revoke_reason  character varying(256)
);

COMMENT ON TABLE device_mqtt_credential IS '设备 MQTT 独立凭据（S1）；username=deviceId，明文仅用于生成 EMQX bootstrap';
