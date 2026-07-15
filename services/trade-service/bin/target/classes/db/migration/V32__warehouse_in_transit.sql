-- 在途库存：出库发运后至补货签收前

CREATE TABLE IF NOT EXISTS warehouse_in_transit (
    transit_id      BIGSERIAL    PRIMARY KEY,
    outbound_id     BIGINT       NOT NULL REFERENCES warehouse_outbound (outbound_id),
    device_id       VARCHAR(64)  NOT NULL,
    sku_id          VARCHAR(64)  NOT NULL,
    batch_no        VARCHAR(64)  NOT NULL,
    quantity        INT          NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'IN_TRANSIT',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    received_at     TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_wh_transit_device_status ON warehouse_in_transit (device_id, status);
CREATE INDEX IF NOT EXISTS idx_wh_transit_outbound_device ON warehouse_in_transit (outbound_id, device_id, status);
