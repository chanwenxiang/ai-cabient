-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- TABLES: balance_refund_allocation（演示级行数，ADD COLUMN 短锁）
-- AU-137（2026-10-18 批次 4）：余额退款切片补状态列——续退判定从「request 表状态推断」
--        改为「切片行权威」。根因：resumePartialRefund 的 remainCents 按 Σ全部切片（含
--        失败已冲回）计算 ⇒ 部分成功+部分失败时 remain=0 且 resumed.isEmpty() ⇒ 谎报
--        REFUNDED，用户损失失败切片金额（peer 真库对账发现，主审亲验闭环）。
-- 存量语义：旧数据无行级成败记录——REFUNDED 单切片必成功；PARTIAL/FAILED 单的失败切片
--        已冲回但无痕迹。按「已出账（SUCCEEDED）」保守处理（不改历史对账语义，只对
--        未来行为权威）；存量 PARTIAL/FAILED 单续退前以页内金额核对为准。
-- NOTES: ADD COLUMN 带 NOT NULL DEFAULT，PG 11+ 不重写表；行数演示级。
-- ROLLBACK: ALTER TABLE balance_refund_allocation DROP COLUMN status

ALTER TABLE balance_refund_allocation
    ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'SUCCEEDED';

COMMENT ON COLUMN balance_refund_allocation.status IS
    '切片渠道状态（AU-137）：PROCESSING 受理中 / SUCCEEDED 成功 / FAILED 失败已冲回；存量行按 SUCCEEDED 保守处理';
