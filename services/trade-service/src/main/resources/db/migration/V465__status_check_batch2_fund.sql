-- MIGRATION_KIND: schema
-- COMPETITOR_REF: CB-036
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: medium（7 条 ADD CONSTRAINT ... CHECK ... NOT VALID：只取短暂 ACCESS EXCLUSIVE 且**不扫存量行**；
--            不带 NOT VALID 会全表扫并持锁。VALIDATE CONSTRAINT 解耦为部署前脏值探针后的运维动作）
-- TABLES: balance_refund_request / merchant_withdraw_request / line_withdraw_request / merchant_settlement_bill / supplier_payable / device_rental_bill / order_revenue_split（资金链第 2 批，本批 7 列）
-- NOTES: R20 P3-1 第 2 批（docs/AUDIT_FOUR_END_R20_2026-11-12.md §二十三 蓝图 / §二十七 回执）。
--        值域由 check-status-enum-parity.mjs 自动对齐 SysDictBootstrap 字典族 ↔ shared-dict ↔ CHECK 字面量，
--        三方双向相等、无人工枚举白名单；新增状态未同步约束 ⇒ CI 红。
--        🔴 禁止用 dev 稀疏值域推枚举（dev 100 个状态列仅 13 个有 CHECK、distinct 仅 113 行）。
--        两条新增自动判据：① 目标列 DDL DEFAULT 必须 ∈ CHECK 值域（否则默认插入即被拒）；
--        ② 带实体类名锚点的源码写入值 set(Cls::getStatus/X, ...) 必须 ⊆ CHECK 值域。
--        CB-036 边界：可扩展经营归属字段仍走字典零迁移，本批只覆盖封闭状态机列（有意不同）。
--        同行公开资料只披露状态枚举、未披露 DB 约束形态 ⇒ 无公开规格，属自己的设计判断（铁律 47）。
--        CHECK (col IN (...)) 对 NULL 天然放行（SQL 三值逻辑，CHECK 只在 FALSE 时拒绝）。
--        部署前脏值探针（逐列，NULL 显式放行）：
--          SELECT DISTINCT <col> FROM <table> WHERE <col> IS NOT NULL
--            AND UPPER(TRIM(<col>)) NOT IN (<下方同一字面量集合>);
--        零脏值后再按表执行 ALTER TABLE <table> VALIDATE CONSTRAINT <constraint>;
--        ⚠️ VALIDATE 故意不写进迁移：大表全扫 + 存量脏值会让 Flyway 失败＝部署失败。

ALTER TABLE balance_refund_request
  ADD CONSTRAINT chk_balance_refund_request_status
  CHECK (status IN ('FAILED','PENDING_REVIEW','REFUNDED','REJECTED'))
  NOT VALID;

ALTER TABLE merchant_withdraw_request
  ADD CONSTRAINT chk_merchant_withdraw_request_status
  CHECK (status IN ('APPROVED','CANCELLED','FAILED','PAID','PAYING','PENDING_REVIEW','REJECTED'))
  NOT VALID;

ALTER TABLE line_withdraw_request
  ADD CONSTRAINT chk_line_withdraw_request_status
  CHECK (status IN ('APPROVED','CANCELLED','FAILED','PAID','PAYING','PENDING_REVIEW','REJECTED'))
  NOT VALID;

ALTER TABLE merchant_settlement_bill
  ADD CONSTRAINT chk_merchant_settlement_bill_status
  CHECK (status IN ('CONFIRMED','PENDING'))
  NOT VALID;

ALTER TABLE supplier_payable
  ADD CONSTRAINT chk_supplier_payable_status
  CHECK (status IN ('CLOSED','PAID','PARTIAL','UNPAID'))
  NOT VALID;

ALTER TABLE device_rental_bill
  ADD CONSTRAINT chk_device_rental_bill_status
  CHECK (status IN ('PAID','UNPAID','VOID'))
  NOT VALID;

ALTER TABLE order_revenue_split
  ADD CONSTRAINT chk_order_revenue_split_status
  CHECK (status IN ('ACCRUED','LEDGER_ONLY','PENDING','SETTLED','SUCCESS','VOIDED','WECHAT_FAILED','WECHAT_SUBMITTED'))
  NOT VALID;
