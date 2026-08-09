-- V165: 积分过期管理 / 优惠券临期提醒 / 沉睡用户召回

-- 1) 积分日志：过期处理与提醒去重标记
ALTER TABLE member_points_log
    ADD COLUMN IF NOT EXISTS expired_at TIMESTAMPTZ;
ALTER TABLE member_points_log
    ADD COLUMN IF NOT EXISTS reminded_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_member_points_expire_pending
    ON member_points_log (expire_at) WHERE expired_at IS NULL;

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
