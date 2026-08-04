-- M4 商户补货要货：要货单 + 行表，衔接 replenishment_task / WMS

CREATE TABLE IF NOT EXISTS merchant_replenishment_request (
    request_id            BIGSERIAL PRIMARY KEY,
    merchant_id           VARCHAR(64)  NOT NULL REFERENCES merchant(merchant_id),
    device_id             VARCHAR(64)  NOT NULL REFERENCES device_info(device_id),
    status                VARCHAR(16)  NOT NULL DEFAULT 'SUBMITTED',
    notes                 VARCHAR(512),
    created_by            BIGINT       NOT NULL,
    submitted_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    reviewed_at           TIMESTAMPTZ,
    reviewer_id           BIGINT,
    replenishment_task_id BIGINT,
    outbound_id           BIGINT,
    reject_reason         VARCHAR(512),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_merchant_replen_req_merchant ON merchant_replenishment_request (merchant_id, submitted_at DESC);
CREATE INDEX IF NOT EXISTS idx_merchant_replen_req_device ON merchant_replenishment_request (device_id, status);
CREATE INDEX IF NOT EXISTS idx_merchant_replen_req_status ON merchant_replenishment_request (status, submitted_at DESC);

CREATE TABLE IF NOT EXISTS merchant_replenishment_request_line (
    line_id        BIGSERIAL PRIMARY KEY,
    request_id     BIGINT       NOT NULL REFERENCES merchant_replenishment_request(request_id) ON DELETE CASCADE,
    sku_id         VARCHAR(64)  NOT NULL,
    sku_name       VARCHAR(128),
    suggested_qty  INT          NOT NULL DEFAULT 0,
    requested_qty  INT          NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_merchant_replen_req_line_req ON merchant_replenishment_request_line (request_id);

ALTER TABLE replenishment_task ADD COLUMN IF NOT EXISTS request_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_replenishment_task_request ON replenishment_task (request_id) WHERE request_id IS NOT NULL;

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order) VALUES
    (223, 200, 'merchant:replenishment:request', '补货要货', 'C', '/merchant/replenishment', 23)
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 6, permission_id FROM ops_permission WHERE perm_code = 'merchant:replenishment:request'
ON CONFLICT DO NOTHING;

-- 演示：一条待审核要货（CAB-001 低库存牛奶）
INSERT INTO merchant_replenishment_request (merchant_id, device_id, status, notes, created_by, submitted_at)
SELECT 'MCH-DEFAULT', 'CAB-001', 'SUBMITTED', '演示：柜内牛奶库存偏低', 100000002, NOW() - INTERVAL '2 hours'
WHERE NOT EXISTS (
    SELECT 1 FROM merchant_replenishment_request
    WHERE device_id = 'CAB-001' AND status = 'SUBMITTED' AND notes = '演示：柜内牛奶库存偏低'
);

INSERT INTO merchant_replenishment_request_line (request_id, sku_id, sku_name, suggested_qty, requested_qty)
SELECT r.request_id, 'SKU-MILK-001', '纯牛奶 250ml', 5, 5
FROM merchant_replenishment_request r
WHERE r.device_id = 'CAB-001' AND r.status = 'SUBMITTED' AND r.notes = '演示：柜内牛奶库存偏低'
  AND NOT EXISTS (
      SELECT 1 FROM merchant_replenishment_request_line l
      WHERE l.request_id = r.request_id AND l.sku_id = 'SKU-MILK-001'
  );
