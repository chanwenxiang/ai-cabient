-- COMPETITOR_REF: CB-016
-- MIGRATION_KIND: backfill
-- MIGRATION_REVIEWED: yes
-- TABLES: supplier_payable_entry（新表）；按既有 supplier_payable 逐条种 OPENING 期初快照
-- LOCK_RISK: low
-- ROLLBACK: DROP TABLE supplier_payable_entry（流水启用前的历史月份本就无流水数据，
--           删除不影响主表 supplier_payable 的任何字段）
-- NOTES: 2026-10-08 E3（缺口 #8 供应商月度对账单，竞品口径见 docs/COMPETITOR_BENCHMARK.md CB-016）。
--
--   🔴 为什么必须建流水表：supplier_payable.amount_cents 是**原地修改的快照**
--   （收货累加 / 退货冲减都改同一行，见 SupplierPayableService.doRecordReceive/doRecordReturn），
--   「期初余额 / 本期发生」从快照倒算必然漂移。行业对账单公式（勤策 ERP 原文）：
--   **期末余额 = 期初余额 + 本期应付 − 本期付款**，只有单据/流水驱动才撑得起期初口径。
--
--   职责边界（对齐 CB-016 结论）：
--   - entry_type = RECEIVE（收货累加）/ RETURN（退货冲减，记**实际冲减额**）/ OPENING（启用快照）
--   - 付款**不**插 entry：付款流水复用既有 supplier_payment（V159），
--     聚合时两表各取一半，避免同一笔钱两处记账
--   - OPENING 种子金额 = 迁移执行那一刻的 amount_cents（启用前累计发生总额）；
--     此后从该时刻起任何月份「期初+发生=期末」精确成立；
--     **启用前的历史月份无流水**，对账单如实显示无数据，不编造期初

-- 应付事件流水：每笔收货累加 / 退货冲减 / 期初快照一行，只增不改（append-only）。
CREATE TABLE IF NOT EXISTS supplier_payable_entry (
    entry_id           BIGSERIAL    PRIMARY KEY,
    supplier_id        VARCHAR(32)  NOT NULL REFERENCES supplier (supplier_id),
    payable_id         BIGINT       NOT NULL REFERENCES supplier_payable (payable_id),
    purchase_order_id  BIGINT       NOT NULL,
    entry_type         VARCHAR(16)  NOT NULL CHECK (entry_type IN ('RECEIVE', 'RETURN', 'OPENING')),
    amount_cents       BIGINT       NOT NULL CHECK (amount_cents >= 0),
    operator_id        BIGINT,
    notes              VARCHAR(256),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_supplier_payable_entry_supplier_time
    ON supplier_payable_entry (supplier_id, created_at);
CREATE INDEX IF NOT EXISTS idx_supplier_payable_entry_payable
    ON supplier_payable_entry (payable_id);

-- 期初快照：流水系统启用那一刻，每条应付的余额即「启用前累计发生额」。
-- 用 NOW() 而非 payable.created_at：OPENING 语义是「本时刻为止的累计」，
-- 打在历史时点会让启用当月的「本期发生」虚增。
INSERT INTO supplier_payable_entry
    (supplier_id, payable_id, purchase_order_id, entry_type, amount_cents, operator_id, notes, created_at)
SELECT p.supplier_id, p.payable_id, p.purchase_order_id, 'OPENING', p.amount_cents, NULL,
       'V326 流水启用期初快照（启用前累计发生额）', NOW()
FROM supplier_payable p;
