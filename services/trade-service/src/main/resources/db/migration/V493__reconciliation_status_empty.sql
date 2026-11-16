-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes（只扩对账状态枚举：EMPTY=两侧无数据，和 MATCHED 已平账区分）
-- COMPETITOR_REF: CB-061（对账/流水口径）
-- R22 §8.4（2026-11-16 按推荐修）：两侧皆 0 不再冒充 MATCHED；资金判定不变，仅状态语义。
-- LOCK_RISK: low（payment_reconciliation 小表；CHECK 先 NOT VALID，避免存量全扫）
-- TABLES: payment_reconciliation

ALTER TABLE payment_reconciliation
  DROP CONSTRAINT IF EXISTS chk_payment_reconciliation_status;

ALTER TABLE payment_reconciliation
  ADD CONSTRAINT chk_payment_reconciliation_status
  CHECK (status IN ('FAILED','MATCHED','MISMATCH','PENDING','EMPTY'))
  NOT VALID;
