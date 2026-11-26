-- MIGRATION_KIND: schema
-- COMPETITOR_REF: CB-088
-- MIGRATION_REVIEWED: yes（服务号通知：渠道独立身份与发送防重）
-- LOCK_RISK: high（user_info 为热表；新列全空，索引扫描仍会短锁写入，须低峰执行）
-- TABLES: user_info, notification_log
-- NOTES: 公众号 openid 与小程序 openid 分列；appid+openid 联合唯一，换服务号时旧绑定不误用。

ALTER TABLE user_info
  ADD COLUMN IF NOT EXISTS wx_official_open_id VARCHAR(64),
  ADD COLUMN IF NOT EXISTS wx_official_app_id VARCHAR(64),
  ADD COLUMN IF NOT EXISTS wx_official_bound_at TIMESTAMPTZ;

CREATE UNIQUE INDEX IF NOT EXISTS uk_user_wx_official_identity
  ON user_info (wx_official_app_id, wx_official_open_id)
  WHERE wx_official_app_id IS NOT NULL
    AND btrim(wx_official_app_id) <> ''
    AND wx_official_open_id IS NOT NULL
    AND btrim(wx_official_open_id) <> '';

ALTER TABLE notification_log
  ADD COLUMN IF NOT EXISTS client_msg_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_notification_log_client_msg_id
  ON notification_log (client_msg_id)
  WHERE client_msg_id IS NOT NULL;

COMMENT ON COLUMN user_info.wx_official_open_id IS '服务号网页授权获得的公众号 OpenID；不得复用小程序 wx_open_id';
COMMENT ON COLUMN user_info.wx_official_app_id IS '签发该公众号 OpenID 的服务号 AppID；与 openid 联合唯一';
COMMENT ON COLUMN user_info.wx_official_bound_at IS '最近一次服务号通知绑定时间';
COMMENT ON COLUMN notification_log.client_msg_id IS '服务号模板消息防重 ID；Kafka 重试保持不变';
