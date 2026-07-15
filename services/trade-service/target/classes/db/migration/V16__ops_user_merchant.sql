-- 运营账号与商户数据权限绑定（多商户运营员只能看自己的设备/订单）

CREATE TABLE IF NOT EXISTS ops_user_merchant (
    user_id     BIGINT       NOT NULL,
    merchant_id VARCHAR(32)  NOT NULL REFERENCES merchant(merchant_id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, merchant_id)
);

CREATE INDEX IF NOT EXISTS idx_ops_user_merchant_merchant ON ops_user_merchant (merchant_id);
