-- V76: 数据库完整性与性能优化
-- 注意：PG 不支持 ADD CONSTRAINT IF NOT EXISTS；商户表名为 merchant（非 merchant_info）。
-- 加盟/线长/会员/券等表由后续正式迁移创建，此处不再创建冲突 stub。

-- 唯一约束
ALTER TABLE sku_catalog DROP CONSTRAINT IF EXISTS uk_sku_name;
ALTER TABLE sku_catalog ADD CONSTRAINT uk_sku_name UNIQUE (sku_name);

ALTER TABLE merchant DROP CONSTRAINT IF EXISTS uk_merchant_name;
ALTER TABLE merchant ADD CONSTRAINT uk_merchant_name UNIQUE (merchant_name);

ALTER TABLE device_info DROP CONSTRAINT IF EXISTS uk_device_name;
ALTER TABLE device_info ADD CONSTRAINT uk_device_name UNIQUE (device_name);

-- 外键（订单行 FK 已由 V70 建立；此处仅补会话→用户）
ALTER TABLE shopping_session DROP CONSTRAINT IF EXISTS fk_session_user;
ALTER TABLE shopping_session
    ADD CONSTRAINT fk_session_user
    FOREIGN KEY (user_id) REFERENCES user_info(user_id) ON DELETE CASCADE;

-- 性能索引
CREATE INDEX IF NOT EXISTS idx_order_user_created ON cabinet_order (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_session_device_state_time ON shopping_session (device_id, state, open_time DESC);

-- 多级分账
CREATE TABLE IF NOT EXISTS revenue_share_rule (
    rule_id BIGSERIAL PRIMARY KEY,
    rule_name VARCHAR(128) NOT NULL,
    rule_type VARCHAR(32) NOT NULL,
    target_id VARCHAR(64),
    device_scope VARCHAR(32) NOT NULL DEFAULT 'ALL',
    share_percent REAL NOT NULL,
    priority INT NOT NULL DEFAULT 0,
    effective_start DATE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS revenue_share_detail (
    share_id BIGSERIAL PRIMARY KEY,
    order_id VARCHAR(32) NOT NULL,
    rule_type VARCHAR(32) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    share_amount_cents INT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_share_detail_order ON revenue_share_detail (order_id);
