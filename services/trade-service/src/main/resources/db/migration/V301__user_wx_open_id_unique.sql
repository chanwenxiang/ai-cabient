-- MIGRATION_KIND: backfill
-- MIGRATION_REVIEWED: yes
-- TABLES: user_info (~演示数十行；生产按账号规模)
-- LOCK_RISK: high
-- ROLLBACK: 不可逆去重；可 DROP INDEX uk_user_wx_open_id
-- NOTES: 先把重复 wx_open_id 置空再 UNIQUE；不与事务型语句拆 CONCURRENTLY。开发库小表可短锁；生产低峰执行。
-- 开发者工具 mock 登录共用 mock_openid_10001；补货员号也写了同一 OpenID，
-- findByWxOpenId 的 selectOne 会 500（expected 1, found 2）。
WITH ranked AS (
    SELECT user_id,
           ROW_NUMBER() OVER (
               PARTITION BY wx_open_id
               ORDER BY CASE WHEN account_type = 'CONSUMER' THEN 0 ELSE 1 END,
                        user_id
           ) AS rn
    FROM user_info
    WHERE wx_open_id IS NOT NULL
      AND btrim(wx_open_id) <> ''
)
UPDATE user_info u
SET wx_open_id = NULL
FROM ranked r
WHERE u.user_id = r.user_id
  AND r.rn > 1;

CREATE UNIQUE INDEX IF NOT EXISTS uk_user_wx_open_id
    ON user_info (wx_open_id)
    WHERE wx_open_id IS NOT NULL AND btrim(wx_open_id) <> '';

COMMENT ON INDEX uk_user_wx_open_id IS '同一微信 OpenID 只能绑一个账号；重复时保留消费者且 user_id 较小者';
