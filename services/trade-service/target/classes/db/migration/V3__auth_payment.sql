-- 用户表扩展：微信 openId（小程序登录绑定）

ALTER TABLE user_info ADD COLUMN IF NOT EXISTS wx_open_id VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_user_wx_open_id ON user_info (wx_open_id);

-- 充值订单（微信支付骨架）

CREATE TABLE IF NOT EXISTS recharge_order (
    order_id       VARCHAR(32)  PRIMARY KEY,
    user_id        BIGINT       NOT NULL REFERENCES user_info(user_id),
    amount_cents   INT          NOT NULL,
    channel        VARCHAR(16)  NOT NULL DEFAULT 'WECHAT',
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    wx_prepay_id   VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    paid_at        TIMESTAMPTZ
);

CREATE INDEX idx_recharge_user ON recharge_order (user_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_user_phone ON user_info (phone_number);
