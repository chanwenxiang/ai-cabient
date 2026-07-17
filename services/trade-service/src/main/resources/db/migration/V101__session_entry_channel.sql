ALTER TABLE shopping_session
    ADD COLUMN IF NOT EXISTS entry_channel VARCHAR(16);

COMMENT ON COLUMN shopping_session.entry_channel IS '扫码入口渠道：WECHAT / ALIPAY';
