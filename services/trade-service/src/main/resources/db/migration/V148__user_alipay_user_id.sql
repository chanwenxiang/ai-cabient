-- 支付宝网页授权用户标识（与免密协议 alipay_agreement_id 分离）
ALTER TABLE user_info ADD COLUMN IF NOT EXISTS alipay_user_id VARCHAR(64);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_alipay_user_id ON user_info (alipay_user_id)
    WHERE alipay_user_id IS NOT NULL AND alipay_user_id <> '';
COMMENT ON COLUMN user_info.alipay_user_id IS '支付宝 OAuth user_id，H5 扫码登录绑定';
