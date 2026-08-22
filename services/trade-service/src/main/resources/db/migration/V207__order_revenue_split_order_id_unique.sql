-- 一订单一分账：去重后加 UNIQUE(order_id)，防止多节点并发重复记账。

DELETE FROM order_revenue_split s
WHERE s.split_id IN (
    SELECT split_id
    FROM (
        SELECT split_id,
               ROW_NUMBER() OVER (
                   PARTITION BY order_id
                   ORDER BY
                       CASE UPPER(COALESCE(status, ''))
                           WHEN 'SUCCESS' THEN 1
                           WHEN 'WECHAT_SUBMITTED' THEN 2
                           WHEN 'SETTLED' THEN 3
                           WHEN 'LEDGER_ONLY' THEN 4
                           WHEN 'ACCRUED' THEN 5
                           WHEN 'VOIDED' THEN 8
                           WHEN 'REVERSED' THEN 9
                           ELSE 6
                       END,
                       created_at DESC,
                       split_id DESC
               ) AS rn
        FROM order_revenue_split
    ) ranked
    WHERE ranked.rn > 1
);

DROP INDEX IF EXISTS idx_order_split_order;

CREATE UNIQUE INDEX IF NOT EXISTS uk_order_revenue_split_order_id
    ON order_revenue_split (order_id);
