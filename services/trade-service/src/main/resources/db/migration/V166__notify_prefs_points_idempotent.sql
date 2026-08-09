-- V166: 消费者通知偏好 + 积分发放幂等保障

-- 1) 通知模板业务分类（用于消费者偏好开关）
ALTER TABLE notification_template
    ADD COLUMN IF NOT EXISTS category VARCHAR(32);

UPDATE notification_template SET category = 'ORDER'     WHERE template_code = 'order_paid';
UPDATE notification_template SET category = 'RECHARGE'  WHERE template_code = 'recharge_success';
UPDATE notification_template SET category = 'COUPON'    WHERE template_code = 'coupon_expiring';
UPDATE notification_template SET category = 'DISPUTE'   WHERE template_code = 'dispute_resolved';
UPDATE notification_template SET category = 'POINTS'    WHERE template_code = 'points_expiring';
UPDATE notification_template SET category = 'RECALL'    WHERE template_code = 'user_recall';
UPDATE notification_template SET category = 'MERCHANT'  WHERE audience = 'MERCHANT';

-- 2) 消费者通知偏好（缺省开启，仅存关闭项）
CREATE TABLE IF NOT EXISTS user_notify_pref (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    category VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, category)
);
CREATE INDEX IF NOT EXISTS idx_user_notify_pref_user ON user_notify_pref (user_id);

-- 3) 积分发放幂等：同一订单对同一会员只返一次积分
CREATE UNIQUE INDEX IF NOT EXISTS idx_member_points_order_unique
    ON member_points_log (member_id, source_type, source_id)
    WHERE source_type = 'ORDER';
