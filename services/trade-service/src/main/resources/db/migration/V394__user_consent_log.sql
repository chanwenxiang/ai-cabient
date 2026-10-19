-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- TABLES: user_consent_log（新建空表）
-- COMPETITOR_REF: CB-059
-- AU-149（2026-10-19 拍板：自助注销闭环）：隐私政策承诺「注销账户联系客服」，但全链路
-- 无注销端点、无同意留痕——PIPL 删除权与小程序审核硬要求（网信办 2026-09 通报明确
-- 「仅支持人工办理且响应迟缓」也算违规）。本表补「服务端同意留痕」：
--   · 登录成功 ⇒ 记 PRIVACY_POLICY 同意（幂等，(user_id, consent_type, policy_version) 唯一）
--   · 自助注销 ⇒ 记 ACCOUNT_DELETE 动作留痕
-- 注销后的资金/交易记录按 PIPL 例外条款保留（交易记录属法定留存），个人标识字段由
-- AccountService.deleteOwnAccount 匿名化，本表仅存 user_id 不存 PII。
-- NOTES: 空表 CREATE，无存量回填；LOG_RISK 低。
-- ROLLBACK: DROP TABLE user_consent_log

CREATE TABLE IF NOT EXISTS user_consent_log (
    log_id        BIGSERIAL PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    consent_type  VARCHAR(32)  NOT NULL,
    policy_version VARCHAR(32) NOT NULL,
    consented_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_user_consent_log_user_type_version
    ON user_consent_log (user_id, consent_type, policy_version);
CREATE INDEX IF NOT EXISTS idx_user_consent_log_user
    ON user_consent_log (user_id, consented_at DESC);

COMMENT ON TABLE user_consent_log IS
    '用户同意留痕（AU-149）：PRIVACY_POLICY=登录时隐私政策同意；ACCOUNT_DELETE=自助注销动作';
