-- MIGRATION_KIND: backfill（user_info 加列并迁移历史身份）
-- COMPETITOR_REF: CB-088
-- MIGRATION_REVIEWED: yes（消费者/商户小程序身份分列，避免同一 user_info 行双端 openid 互相覆盖）
-- LOCK_RISK: high（user_info 热表加列并回填运营账号身份、重建局部唯一索引；须低峰执行）
-- TABLES: user_info
-- NOTES: 保留 wx_open_id 作为消费者小程序 OpenID；新增 wx_merchant_open_id 给商户端。历史运营账号的 wx_open_id 迁入商户列并清空 consumer 列，防止消费者通知误用商户 openid。

ALTER TABLE user_info
    ADD COLUMN IF NOT EXISTS wx_merchant_open_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS wx_consumer_app_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS wx_merchant_app_id VARCHAR(64);

UPDATE user_info
SET wx_merchant_open_id = wx_open_id,
    wx_open_id = NULL
WHERE (account_type = 'OPERATOR' OR user_id >= 100000000)
  AND wx_open_id IS NOT NULL
  AND btrim(wx_open_id) <> '';

CREATE UNIQUE INDEX IF NOT EXISTS uk_user_wx_merchant_open_id
    ON user_info (wx_merchant_open_id)
    WHERE wx_merchant_open_id IS NOT NULL
      AND btrim(wx_merchant_open_id) <> '';

COMMENT ON COLUMN user_info.wx_open_id IS '消费者小程序 OpenID；仅消费者 appid 签发，不得写商户端 openid';
COMMENT ON COLUMN user_info.wx_merchant_open_id IS '商户小程序 OpenID；仅商户 appid 签发，不得写消费者端 openid';
COMMENT ON COLUMN user_info.wx_consumer_app_id IS '消费者小程序 appid（签发 wx_open_id）';
COMMENT ON COLUMN user_info.wx_merchant_app_id IS '商户小程序 appid（签发 wx_merchant_open_id）';
