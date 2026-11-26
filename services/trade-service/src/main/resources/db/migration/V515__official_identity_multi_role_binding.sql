-- MIGRATION_KIND: schema
-- COMPETITOR_REF: CB-088
-- MIGRATION_REVIEWED: yes（同一自然人可同时拥有消费者与商户操作员角色）
-- LOCK_RISK: high（user_info 热表；删除并重建局部唯一索引会阻塞写入，须低峰执行）
-- TABLES: user_info
-- NOTES: V514 的 appid+openid 唯一约束阻止同一微信先绑商户再绑消费者；改为 user_id+appid 唯一，保留同账号同服务号防重复绑定能力。

DROP INDEX IF EXISTS uk_user_wx_official_identity;

CREATE UNIQUE INDEX IF NOT EXISTS uk_user_wx_official_binding
  ON user_info (user_id, wx_official_app_id)
  WHERE wx_official_app_id IS NOT NULL
    AND btrim(wx_official_app_id) <> ''
    AND wx_official_open_id IS NOT NULL
    AND btrim(wx_official_open_id) <> '';

COMMENT ON INDEX uk_user_wx_official_binding IS '每个本地账号对每个服务号最多绑定一次；同一服务号 OpenID 可绑定消费者与商户多个角色账号';
