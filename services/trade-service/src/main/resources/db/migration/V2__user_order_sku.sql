-- 用户与账户（参考旧 m8_user_info / m8_user_account）

CREATE TABLE IF NOT EXISTS user_info (
    user_id       BIGINT       PRIMARY KEY,
    phone_number  VARCHAR(32)  NOT NULL,
    name          VARCHAR(64),
    verified      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS user_account (
    user_id       BIGINT       PRIMARY KEY REFERENCES user_info(user_id),
    balance_cents INT          NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 订单（关门结算后生成）

CREATE TABLE IF NOT EXISTS cabinet_order (
    order_id          VARCHAR(32)  PRIMARY KEY,
    session_id        VARCHAR(32)  NOT NULL REFERENCES shopping_session(session_id),
    user_id           BIGINT       NOT NULL,
    device_id         VARCHAR(64)  NOT NULL,
    total_amount_cents INT         NOT NULL DEFAULT 0,
    status            VARCHAR(16)  NOT NULL DEFAULT 'PAID',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS cabinet_order_line (
    id                BIGSERIAL    PRIMARY KEY,
    order_id          VARCHAR(32)  NOT NULL REFERENCES cabinet_order(order_id),
    sku_id            VARCHAR(64)  NOT NULL,
    sku_name          VARCHAR(128),
    quantity          INT          NOT NULL,
    unit_price_cents  INT          NOT NULL,
    line_amount_cents INT          NOT NULL
);

CREATE INDEX idx_order_session ON cabinet_order (session_id);

-- 商品 SKU（识别与计价）

CREATE TABLE IF NOT EXISTS sku_catalog (
    sku_id            VARCHAR(64)  PRIMARY KEY,
    sku_name          VARCHAR(128) NOT NULL,
    price_cents       INT          NOT NULL,
    weight_grams      INT,
    vision_enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 开发环境种子数据

INSERT INTO device_info (device_id, device_name, device_type, online_status)
VALUES ('CAB-001', '测试柜-001', 'AI_CABINET_V1', 'OFFLINE')
ON CONFLICT (device_id) DO NOTHING;

INSERT INTO user_info (user_id, phone_number, name, verified)
VALUES (10001, '13800138000', '测试用户', TRUE)
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO user_account (user_id, balance_cents)
VALUES (10001, 10000)
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO sku_catalog (sku_id, sku_name, price_cents)
VALUES ('SKU-DEMO-001', '演示商品-可乐', 350)
ON CONFLICT (sku_id) DO NOTHING;
