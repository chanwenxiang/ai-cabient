-- Re-clamp device_sku_lot quantities that exceed device_slot.max_level (e.g. CAB-001 A3 water 7/6).
-- 1) Relocate surplus into sibling slots with same SKU when headroom exists.
-- 2) Clamp any remaining over-capacity lots.
-- 3) Resync physical qty + device_sku_inventory aggregates.

DO $$
DECLARE
    ov RECORD;
    tgt RECORD;
    move_qty INT;
    src_lot_id VARCHAR;
    src_lot_qty INT;
    tgt_lot_id VARCHAR;
BEGIN
    LOOP
        SELECT sb.device_id,
               sb.slot_code,
               sb.sku_id,
               sb.book_qty - s.max_level AS surplus
        INTO ov
        FROM (
            SELECT l.device_id,
                   UPPER(l.slot_id) AS slot_code,
                   l.sku_id,
                   SUM(l.quantity)::int AS book_qty
            FROM device_sku_lot l
            WHERE l.status IN ('ON_SALE', 'NEAR_EXPIRY')
              AND l.slot_id IS NOT NULL
              AND l.slot_id <> ''
            GROUP BY l.device_id, UPPER(l.slot_id), l.sku_id
        ) sb
        JOIN device_slot s
          ON s.device_id = sb.device_id
         AND UPPER(s.slot_code) = sb.slot_code
        WHERE s.enabled
          AND s.max_level > 0
          AND sb.book_qty > s.max_level
        ORDER BY sb.device_id, sb.slot_code
        LIMIT 1;

        EXIT WHEN NOT FOUND;

        FOR tgt IN
            SELECT UPPER(s.slot_code) AS slot_code,
                   s.max_level - COALESCE(sub.book_qty, 0) AS headroom
            FROM device_slot s
            LEFT JOIN (
                SELECT l.device_id,
                       UPPER(l.slot_id) AS slot_code,
                       l.sku_id,
                       SUM(l.quantity)::int AS book_qty
                FROM device_sku_lot l
                WHERE l.status IN ('ON_SALE', 'NEAR_EXPIRY')
                  AND l.slot_id IS NOT NULL
                  AND l.slot_id <> ''
                GROUP BY l.device_id, UPPER(l.slot_id), l.sku_id
            ) sub
              ON sub.device_id = s.device_id
             AND sub.slot_code = UPPER(s.slot_code)
             AND sub.sku_id = ov.sku_id
            WHERE s.device_id = ov.device_id
              AND s.assigned_sku_id = ov.sku_id
              AND UPPER(s.slot_code) <> ov.slot_code
              AND s.enabled
              AND s.max_level > 0
              AND s.max_level - COALESCE(sub.book_qty, 0) > 0
            ORDER BY s.row_no, s.col_no
        LOOP
            EXIT WHEN ov.surplus <= 0;

            move_qty := LEAST(ov.surplus, tgt.headroom);

            SELECT l.lot_id, l.quantity
            INTO src_lot_id, src_lot_qty
            FROM device_sku_lot l
            WHERE l.device_id = ov.device_id
              AND UPPER(l.slot_id) = ov.slot_code
              AND l.sku_id = ov.sku_id
              AND l.status IN ('ON_SALE', 'NEAR_EXPIRY')
              AND l.quantity > 0
            ORDER BY l.quantity DESC, l.lot_id
            LIMIT 1
            FOR UPDATE;

            IF src_lot_id IS NULL THEN
                EXIT;
            END IF;

            UPDATE device_sku_lot
            SET quantity = quantity - move_qty,
                updated_at = NOW()
            WHERE lot_id = src_lot_id;

            SELECT l.lot_id
            INTO tgt_lot_id
            FROM device_sku_lot l
            WHERE l.device_id = ov.device_id
              AND UPPER(l.slot_id) = tgt.slot_code
              AND l.sku_id = ov.sku_id
              AND l.status IN ('ON_SALE', 'NEAR_EXPIRY')
            ORDER BY l.quantity DESC, l.lot_id
            LIMIT 1
            FOR UPDATE;

            IF tgt_lot_id IS NOT NULL THEN
                UPDATE device_sku_lot
                SET quantity = quantity + move_qty,
                    updated_at = NOW()
                WHERE lot_id = tgt_lot_id;
            ELSE
                INSERT INTO device_sku_lot (
                    lot_id, device_id, sku_id, batch_no, production_date, expiry_date,
                    quantity, slot_id, status, created_at, updated_at
                )
                SELECT
                    'LRELOC' || UPPER(SUBSTRING(MD5(RANDOM()::text) FROM 1 FOR 8)),
                    ov.device_id,
                    ov.sku_id,
                    COALESCE(src.batch_no, 'B-RELOC'),
                    src.production_date,
                    COALESCE(src.expiry_date, CURRENT_DATE + 180),
                    move_qty,
                    tgt.slot_code,
                    'ON_SALE',
                    NOW(),
                    NOW()
                FROM device_sku_lot src
                WHERE src.lot_id = src_lot_id;
            END IF;

            ov.surplus := ov.surplus - move_qty;
        END LOOP;

        IF ov.surplus > 0 THEN
            UPDATE device_sku_lot AS l
            SET quantity = GREATEST(0, l.quantity - ov.surplus),
                updated_at = NOW()
            FROM device_slot s
            WHERE l.device_id = ov.device_id
              AND UPPER(l.slot_id) = ov.slot_code
              AND l.sku_id = ov.sku_id
              AND l.status IN ('ON_SALE', 'NEAR_EXPIRY')
              AND s.device_id = l.device_id
              AND UPPER(s.slot_code) = ov.slot_code
              AND l.lot_id = (
                  SELECT l2.lot_id
                  FROM device_sku_lot l2
                  WHERE l2.device_id = ov.device_id
                    AND UPPER(l2.slot_id) = ov.slot_code
                    AND l2.sku_id = ov.sku_id
                    AND l2.status IN ('ON_SALE', 'NEAR_EXPIRY')
                  ORDER BY l2.quantity DESC
                  LIMIT 1
              );
        END IF;
    END LOOP;
END $$;

UPDATE device_sku_lot AS l
SET quantity = s.max_level,
    updated_at = NOW()
FROM device_slot s
WHERE l.device_id = s.device_id
  AND UPPER(COALESCE(l.slot_id, '')) = UPPER(s.slot_code)
  AND s.max_level > 0
  AND l.quantity > s.max_level
  AND l.status IN ('ON_SALE', 'NEAR_EXPIRY');

UPDATE device_slot AS s
SET last_physical_qty = LEAST(COALESCE(s.last_physical_qty, sub.qty), s.max_level),
    last_physical_at = NOW(),
    updated_at = NOW()
FROM (
    SELECT l.device_id,
           UPPER(l.slot_id) AS slot_code,
           SUM(l.quantity)::int AS qty
    FROM device_sku_lot l
    WHERE l.status IN ('ON_SALE', 'NEAR_EXPIRY')
      AND l.slot_id IS NOT NULL
      AND l.slot_id <> ''
    GROUP BY l.device_id, UPPER(l.slot_id)
) sub
WHERE s.device_id = sub.device_id
  AND UPPER(s.slot_code) = sub.slot_code
  AND s.max_level > 0
  AND COALESCE(s.last_physical_qty, 0) > s.max_level;

UPDATE device_sku_inventory AS inv
SET quantity = COALESCE(lot_sum.qty, 0),
    updated_at = NOW()
FROM (
    SELECT device_id, sku_id, SUM(quantity)::int AS qty
    FROM device_sku_lot
    WHERE status IN ('ON_SALE', 'NEAR_EXPIRY')
    GROUP BY device_id, sku_id
) AS lot_sum
WHERE inv.device_id = lot_sum.device_id
  AND inv.sku_id = lot_sum.sku_id;
