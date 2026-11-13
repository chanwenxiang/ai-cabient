-- V165: 积分过期管理 / 优惠券临期提醒 / 沉睡用户召回
-- MIGRATION_IMMUTABLE_EXEMPT: S14 修复批——member_points_log 已于 V136 DROP，原第 1 节的
--   ALTER/CREATE INDEX 是死语句（任何应用了 V136 的库执行到此处必 relation does not exist），
--   移除后全新空库可按序构建；测试库重建消化 checksum 变化（生产库不适用本豁免）。

-- 1) 积分日志：过期处理与提醒去重标记
-- member_points_log 已于 V136 DROP，本节原 ALTER/INDEX 为死语句，已移除（S14 表名声明集修复）。

-- 2) 优惠券：临期提醒去重标记
ALTER TABLE user_coupon
    ADD COLUMN IF NOT EXISTS reminded_at TIMESTAMPTZ;

-- 3) 新通知模板
INSERT INTO notification_template
    (template_code, template_name, channel, channels, title_template, body_template, audience)
VALUES
    ('points_expiring', '积分即将过期', 'IN_APP', 'IN_APP,WECHAT_SUBSCRIBE',
     '积分即将过期',
     '您有 {points} 积分将于 {expireAt} 过期，请及时前往积分中心使用。',
     'CONSUMER'),
    ('user_recall', '沉睡用户召回礼', 'IN_APP', 'IN_APP,WECHAT_SUBSCRIBE,SMS',
     '好久不见，欢迎回来',
     '给您送上一张「{couponName}」优惠券，欢迎回来选购。',
     'CONSUMER')
ON CONFLICT (template_code) DO NOTHING;
