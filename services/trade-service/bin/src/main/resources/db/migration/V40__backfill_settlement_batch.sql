-- 回填历史分账记录的结算批次号，便于商户按批次对账

UPDATE order_revenue_split
SET settlement_batch_no = 'MS-' || TO_CHAR(created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD') || '-' || merchant_id,
    settle_after = COALESCE(settle_after, (DATE(created_at AT TIME ZONE 'Asia/Shanghai') + 1))
WHERE settlement_batch_no IS NULL OR settlement_batch_no = '';
