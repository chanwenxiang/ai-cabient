-- COMPETITOR_REF: CB-020
-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- CB-020③ 渠道费实结化：对账账单回填实结渠道费。
-- 1) payment_platform_bill_line.fee_cents：微信交易账单「手续费」列（官方 27 列 ALL 型 0 基第 22 列）逐行落库；
-- 2) payment_reconciliation.channel_fee_cents：当日账单手续费合计（日×渠道粒度，FundBillService 实结优先展示的数据源）。
-- 两者均可空：NULL = 无手续费数据（历史行 / Mock 账单 / 支付宝未映射），语义区别于 0（账单明确手续费为零），
-- 上层据此走估算兜底而非误显示 0。

ALTER TABLE payment_platform_bill_line ADD COLUMN fee_cents BIGINT;
ALTER TABLE payment_reconciliation ADD COLUMN channel_fee_cents BIGINT;
