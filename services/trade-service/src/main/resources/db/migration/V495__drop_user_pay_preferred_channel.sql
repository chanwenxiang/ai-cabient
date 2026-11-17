-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- TABLES: user_info（消费者主表；行数按用户量，开发库小表）
-- LOCK_RISK: low（DROP COLUMN 只改元数据、不重写表；ACCESS EXCLUSIVE 仅在语句瞬间）
-- ROLLBACK: ALTER TABLE user_info ADD COLUMN pay_preferred_channel VARCHAR(16) NOT NULL DEFAULT 'BALANCE'（旧值不可恢复）
-- NOTES: 2026-11-17 用户拍板删除「用户支付偏好」：扣款渠道只由扫码入口/显式选择决定，
--   严格入口、不跨渠道回落；偏好字段与 PUT /account/pay-preferred 一并移除。
--   删除后应用不再读写该列；保留只会留下一个永不更新、却可能被旧代码误读的脏值。

ALTER TABLE user_info DROP COLUMN IF EXISTS pay_preferred_channel;
