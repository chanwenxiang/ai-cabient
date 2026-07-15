-- Phase D-1: 货道陈列图（Planogram）与通道级运营指标

CREATE TABLE IF NOT EXISTS device_slot (
    device_id           VARCHAR(64)  NOT NULL,
    slot_code           VARCHAR(32)  NOT NULL,
    row_no              INT          NOT NULL DEFAULT 1,
    col_no              INT          NOT NULL DEFAULT 1,
    slot_type           VARCHAR(16)  NOT NULL DEFAULT 'SHELF',
    assigned_sku_id     VARCHAR(64),
    par_level           INT          NOT NULL DEFAULT 0,
    min_level           INT          NOT NULL DEFAULT 0,
    max_level           INT          NOT NULL DEFAULT 0,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    last_physical_qty   INT,
    last_physical_at    TIMESTAMPTZ,
    last_restock_at     TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (device_id, slot_code)
);

CREATE INDEX IF NOT EXISTS idx_device_slot_device ON device_slot (device_id);
CREATE INDEX IF NOT EXISTS idx_device_slot_sku ON device_slot (assigned_sku_id);

-- 演示柜 CAB-001 陈列图（3 行 × 4 列）
INSERT INTO device_slot (device_id, slot_code, row_no, col_no, assigned_sku_id, par_level, min_level, max_level, enabled)
VALUES
    ('CAB-001', 'A1', 1, 1, 'SKU-DEMO-001', 8, 2, 8, TRUE),
    ('CAB-001', 'A2', 1, 2, 'SKU-SODA-001', 8, 2, 8, TRUE),
    ('CAB-001', 'A3', 1, 3, 'SKU-WATER-001', 6, 2, 6, TRUE),
    ('CAB-001', 'A4', 1, 4, 'SKU-WATER-001', 6, 2, 6, TRUE),
    ('CAB-001', 'B1', 2, 1, 'SKU-SNACK-001', 8, 2, 8, TRUE),
    ('CAB-001', 'B2', 2, 2, 'SKU-MILK-001', 6, 2, 6, TRUE),
    ('CAB-001', 'B3', 2, 3, 'SKU-NOODLE-001', 8, 2, 8, TRUE),
    ('CAB-001', 'B4', 2, 4, 'SKU-NOODLE-001', 4, 1, 4, TRUE),
    ('CAB-001', 'C1', 3, 1, NULL, 0, 0, 0, FALSE),
    ('CAB-001', 'C2', 3, 2, NULL, 0, 0, 0, FALSE),
    ('CAB-001', 'C3', 3, 3, NULL, 0, 0, 0, FALSE),
    ('CAB-001', 'C4', 3, 4, NULL, 0, 0, 0, FALSE)
ON CONFLICT (device_id, slot_code) DO NOTHING;
