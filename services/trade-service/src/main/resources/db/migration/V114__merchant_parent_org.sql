-- 商户多级组织：parent_merchant_id 指向上级商户（区域/加盟总部）
ALTER TABLE merchant
    ADD COLUMN IF NOT EXISTS parent_merchant_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_merchant_parent ON merchant (parent_merchant_id);

COMMENT ON COLUMN merchant.parent_merchant_id IS '上级商户 ID；空=根节点。运营账号绑定上级后可见全部下级设备';
