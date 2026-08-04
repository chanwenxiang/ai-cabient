-- 柜机退款策略：NULL/INHERIT=跟随全局；AUTO_REFUND=自助即时退款；DISPUTE_ONLY=仅申诉、运营审核后退款
ALTER TABLE device_info
    ADD COLUMN IF NOT EXISTS refund_policy VARCHAR(32);

COMMENT ON COLUMN device_info.refund_policy IS '退款策略：AUTO_REFUND | DISPUTE_ONLY | NULL(继承全局)';

INSERT INTO system_config (config_key, config_value, description, updated_at)
VALUES (
    'refund.default_policy',
    'AUTO_REFUND',
    '全局默认退款策略：AUTO_REFUND=消费者自助退款；DISPUTE_ONLY=仅申诉、运营审核后退款',
    NOW()
)
ON CONFLICT (config_key) DO NOTHING;
