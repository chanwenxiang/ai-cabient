-- COMPETITOR_REF: CB-074（补货自提自送：商户端采购幂等键由客户端 refNo 承担，DB 兜底防并发双建）
-- KIND: create
-- R4-D2：商户端采购幂等键 refNo（`MP-` 前缀，见 purchase.vue）此前无 DB 约束 ——
-- 同键并发双请求「先查后插」竞态可建两张 CREATED（对照 wallet_ledger uk_wallet_ledger_ref
-- 的 DB 兜底思路）。partial unique 只约束 `MP-` 前缀：运营端人工 refNo（PO- 参考号）
-- 与 NULL 不受影响。`MP-` 键为 2026-11-10 新引入，存量无该前缀行，建索引无历史冲突面。
CREATE UNIQUE INDEX IF NOT EXISTS uk_purchase_order_client_ref
    ON purchase_order (warehouse_id, ref_no)
    WHERE ref_no LIKE 'MP-%';
