-- V104: 组织架构（组织树 + 设备归属）与点位场地合同
CREATE TABLE IF NOT EXISTS ops_org_node (
    node_id    BIGSERIAL PRIMARY KEY,
    parent_id  BIGINT,
    name       VARCHAR(128) NOT NULL,
    node_type  VARCHAR(16) NOT NULL DEFAULT 'BRANCH',  -- HQ | REGION | BRANCH
    sort_order INT NOT NULL DEFAULT 0,
    enabled    BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS ops_device_org (
    id        BIGSERIAL PRIMARY KEY,
    node_id   BIGINT NOT NULL REFERENCES ops_org_node(node_id) ON DELETE CASCADE,
    device_id VARCHAR(64) NOT NULL,
    UNIQUE (device_id)
);

CREATE TABLE IF NOT EXISTS site_contract (
    contract_id        BIGSERIAL PRIMARY KEY,
    device_id          VARCHAR(64) NOT NULL UNIQUE,
    site_name          VARCHAR(128) NOT NULL,
    address            VARCHAR(256),
    landlord_name      VARCHAR(64),
    landlord_phone     VARCHAR(32),
    start_date         DATE,
    end_date           DATE,
    monthly_fee_cents  INT NOT NULL DEFAULT 0,
    status             VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | EXPIRING | EXPIRED
    remark             VARCHAR(256),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
