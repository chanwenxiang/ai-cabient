-- M2 商户点位定价：设备级 SKU 零售价覆盖

ALTER TABLE sku_catalog ADD COLUMN IF NOT EXISTS max_price_cents INT;

CREATE TABLE IF NOT EXISTS device_sku_price (
    device_id           VARCHAR(64)  NOT NULL REFERENCES device_info(device_id),
    sku_id              VARCHAR(64)  NOT NULL REFERENCES sku_catalog(sku_id),
    price_cents         INT          NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by_user_id  BIGINT,
    PRIMARY KEY (device_id, sku_id)
);

CREATE INDEX IF NOT EXISTS idx_device_sku_price_device ON device_sku_price (device_id);

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order) VALUES
    (220, 200, 'merchant:pricing:view', '商品定价查看', 'C', '/merchant/pricing', 20),
    (221, 200, 'merchant:pricing:edit', '商品定价修改', 'C', '/merchant/pricing', 21)
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 6, permission_id FROM ops_permission WHERE perm_code IN ('merchant:pricing:view', 'merchant:pricing:edit')
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 7, permission_id FROM ops_permission WHERE perm_code = 'merchant:pricing:view'
ON CONFLICT DO NOTHING;
