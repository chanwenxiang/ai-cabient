-- session preferred coupon + member price discount
ALTER TABLE shopping_session
    ADD COLUMN IF NOT EXISTS preferred_coupon_id BIGINT;

ALTER TABLE member_level_rule
    ADD COLUMN IF NOT EXISTS price_discount_pct NUMERIC(5, 2) NOT NULL DEFAULT 0;

COMMENT ON COLUMN shopping_session.preferred_coupon_id IS '开门前用户指定优惠券；结算优先用此券，无效则回退自动择优';
COMMENT ON COLUMN member_level_rule.price_discount_pct IS '会员价折扣百分比，如 5=打95折；0=无会员价';

INSERT INTO system_config (config_key, config_value, description, updated_at)
VALUES ('debt.block_open_on_pending', 'true', '有待支付订单时是否禁止开门', NOW())
ON CONFLICT (config_key) DO NOTHING;

