-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- COMPETITOR_REF: CB-046
-- TABLES: device_http_credential（全新独立表，0 行起；设备数级~百级）
-- LOCK_RISK: low
-- ROLLBACK: DROP TABLE device_http_credential（上线初期无数据时可逆）
-- NOTES: V367（审计 A1/P0-2）：设备 HTTP 独立凭据（范式依据=CB-046：微卡硬件指纹/微信 APIv3/
--       简单购/乐摇摇/酷点均为设备级预共享凭据，fleet 共享密钥无行业先例）——OTA check/progress、screen-content、
-- ad-play、sessions/video* 等「设备可达」内部端点从 fleet 共享 INTERNAL_API_KEY 切到
-- 一机一密（deviceId 与凭据查库绑定，消除「任一 APK 泄露=全 fleet 权限」）。
-- 表结构对齐 V290__device_mqtt_credential（签发明文一次性返回、hash 落库、吊销留痕）。
-- 与 V290 拆两表不并表：MQTT 凭据由 EMQX bootstrap 生成器消费（需要明文列），
-- HTTP 凭据校验只需 hash——语义与敏感级不同，各自独立演进。
-- 服务间调用（trade↔device↔jiangyi-gateway）维持 INTERNAL_API_KEY 不变（不在 APK、
-- 生产有 CIDR，见审计 P0-2 根治策略）。
-- 竞品：审计 docs/CODE_AUDIT_REPORT_2026-10-05_FULL.md P0-2（安全工程，无竞品对照）。

CREATE TABLE IF NOT EXISTS device_http_credential (
    device_id      character varying(32) PRIMARY KEY,
    secret_sha256  character varying(64) NOT NULL,
    status         character varying(16) NOT NULL DEFAULT 'ACTIVE',
    issued_by      bigint,
    issued_at      timestamp with time zone NOT NULL DEFAULT NOW(),
    rotated_at     timestamp with time zone,
    revoked_at     timestamp with time zone,
    revoke_reason  character varying(256),
    CONSTRAINT ck_device_http_credential_status CHECK (status IN ('ACTIVE', 'REVOKED'))
);

CREATE INDEX idx_device_http_credential_status ON device_http_credential (status);

COMMENT ON TABLE device_http_credential IS '设备 HTTP 独立凭据（审计 A1/P0-2）：设备可达内部端点一机一密；明文仅签发响应一次性返回，本表只存 sha256';
