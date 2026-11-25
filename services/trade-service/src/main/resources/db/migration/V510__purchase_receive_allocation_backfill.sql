-- COMPETITOR_REF: CB-009
-- MIGRATION_KIND: backfill
-- MIGRATION_REVIEWED: yes
-- TABLES: purchase_receive_allocation
-- NOTES: MA-451 历史收货按仓/批回填；每条 PURCHASE_RECEIVE 必须唯一匹配采购单行(sku)，歧义流水不猜。
WITH candidates AS (
    SELECT m.movement_id,
           m.ref_id,
           m.sku_id,
           m.warehouse_id,
           m.batch_no,
           m.delta_qty,
           m.created_at,
           pol.line_id,
           CASE WHEN pol.batch_no = m.batch_no THEN 1 ELSE 2 END AS match_priority,
           COUNT(*) FILTER (WHERE pol.batch_no = m.batch_no)
               OVER (PARTITION BY m.movement_id) AS batch_match_count,
           COUNT(*) OVER (PARTITION BY m.movement_id) AS sku_match_count,
           ROW_NUMBER() OVER (PARTITION BY m.movement_id ORDER BY
               CASE WHEN pol.batch_no = m.batch_no THEN 1 ELSE 2 END, pol.line_id) AS candidate_rank
    FROM warehouse_movement m
    JOIN purchase_order po
      ON po.purchase_order_id::text = m.ref_id
    JOIN purchase_order_line pol
      ON pol.purchase_order_id = po.purchase_order_id
     AND pol.sku_id = m.sku_id
    WHERE m.movement_type = 'PURCHASE_RECEIVE'
      AND m.ref_type = 'PURCHASE_ORDER'
      AND m.delta_qty > 0
      AND m.ref_id ~ '^[0-9]+$'
      AND m.batch_no IS NOT NULL
      AND pol.received_qty > 0
      AND po.status IN ('PARTIAL_RECEIVED', 'RECEIVED')
), matched AS (
    SELECT movement_id, ref_id, sku_id, warehouse_id, batch_no, delta_qty, created_at, line_id
    FROM candidates
    WHERE candidate_rank = 1
      AND ((match_priority = 1 AND batch_match_count = 1) OR (batch_match_count = 0 AND sku_match_count = 1))
), grouped AS (
    SELECT ref_id::bigint AS purchase_order_id,
           line_id AS purchase_line_id,
           sku_id,
           warehouse_id,
           batch_no,
           SUM(delta_qty)::int AS received_qty,
           MIN(created_at) AS created_at
    FROM matched
    GROUP BY ref_id, line_id, sku_id, warehouse_id, batch_no
)
INSERT INTO purchase_receive_allocation
    (purchase_order_id, purchase_line_id, sku_id, warehouse_id, batch_no,
     received_qty, returned_qty, created_at)
SELECT g.purchase_order_id,
       g.purchase_line_id,
       g.sku_id,
       g.warehouse_id,
       g.batch_no,
       g.received_qty,
       0,
       g.created_at
FROM grouped g
WHERE NOT EXISTS (
    SELECT 1
    FROM purchase_receive_allocation a
    WHERE a.purchase_line_id = g.purchase_line_id
      AND a.warehouse_id = g.warehouse_id
      AND a.batch_no = g.batch_no
);

-- 历史退货的 batch_no/warehouse_id 是当时真实扣减点，按同一仓批回填 returned_qty，
-- 否则迁移后所有已有退货单都会在 validateAllocationTotals 处 fail-closed。
WITH returned AS (
    SELECT a.allocation_id,
           SUM(prl.quantity * pol.pack_qty)::int AS returned_qty
    FROM purchase_receive_allocation a
    JOIN purchase_order_line pol
      ON pol.line_id = a.purchase_line_id
    JOIN purchase_return_line prl
      ON prl.purchase_line_id = a.purchase_line_id
     AND prl.batch_no = a.batch_no
    JOIN purchase_return pr
      ON pr.return_id = prl.return_id
     AND pr.warehouse_id = a.warehouse_id
    GROUP BY a.allocation_id
)
UPDATE purchase_receive_allocation a
SET returned_qty = r.returned_qty
FROM returned r
WHERE a.allocation_id = r.allocation_id
  AND r.returned_qty <= a.received_qty;
