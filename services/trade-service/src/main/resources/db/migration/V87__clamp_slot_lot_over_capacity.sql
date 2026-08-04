-- Fix book qty overflowing device_slot.max_level (e.g. CAB-001 A3 water 16/6).
-- 1) Redistribute surplus into sibling empty slots of the same SKU (up to each max).
-- 2) Clamp any remaining over-capacity lots.
-- 3) Resync device_sku_inventory aggregates from sellable lots.

-- CAB-001 water: A3=16, A4 empty / max 6 each → A3=6, A4=6 (drop leftover 4)
UPDATE device_sku_lot
SET quantity = 6,
    updated_at = NOW()
WHERE device_id = 'CAB-001'
  AND slot_id = 'A3'
  AND sku_id = 'SKU-WATER-001'
  AND quantity > 6;

INSERT INTO device_sku_lot (
    lot_id, device_id, sku_id, batch_no, production_date, expiry_date,
    quantity, slot_id, status, created_at, updated_at
)
SELECT
    'LFIXA4WATER001',
    'CAB-001',
    'SKU-WATER-001',
    COALESCE(src.batch_no, 'B-CAP-FIX'),
    src.production_date,
    COALESCE(src.expiry_date, CURRENT_DATE + 180),
    6,
    'A4',
    'ON_SALE',
    NOW(),
    NOW()
FROM (
    SELECT batch_no, production_date, expiry_date
    FROM device_sku_lot
    WHERE device_id = 'CAB-001' AND slot_id = 'A3' AND sku_id = 'SKU-WATER-001'
    ORDER BY updated_at DESC
    LIMIT 1
) src
WHERE EXISTS (
    SELECT 1 FROM device_slot
    WHERE device_id = 'CAB-001' AND slot_code = 'A4' AND assigned_sku_id = 'SKU-WATER-001'
)
  AND NOT EXISTS (
    SELECT 1 FROM device_sku_lot
    WHERE device_id = 'CAB-001' AND slot_id = 'A4' AND quantity > 0
);

-- Generic clamp: any lot above its slot max_level
UPDATE device_sku_lot AS l
SET quantity = s.max_level,
    updated_at = NOW()
FROM device_slot s
WHERE l.device_id = s.device_id
  AND UPPER(COALESCE(l.slot_id, '')) = UPPER(s.slot_code)
  AND s.max_level > 0
  AND l.quantity > s.max_level;

-- Resync SKU aggregates from ON_SALE lots
UPDATE device_sku_inventory AS inv
SET quantity = COALESCE(lot_sum.qty, 0),
    updated_at = NOW()
FROM (
    SELECT device_id, sku_id, SUM(quantity)::int AS qty
    FROM device_sku_lot
    WHERE status = 'ON_SALE'
    GROUP BY device_id, sku_id
) AS lot_sum
WHERE inv.device_id = lot_sum.device_id
  AND inv.sku_id = lot_sum.sku_id;
