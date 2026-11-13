-- MIGRATION_KIND: schema
-- COMPETITOR_REF: CB-036
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: medium（4 条 ADD CONSTRAINT ... CHECK ... NOT VALID：只取短暂 ACCESS EXCLUSIVE 且**不扫存量行**；
--            不带 NOT VALID 会全表扫并持锁。VALIDATE CONSTRAINT 解耦为部署前脏值探针后的运维动作）
-- TABLES: compensation_task / repair_ticket / ops_exception / user_feedback（争议链第 4 批，本批 4 列）
-- NOTES: R20 P3-1 第 4 批（docs/AUDIT_FOUR_END_R20_2026-11-12.md §二十三 蓝图 / §二十七 回执）。
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

ALTER TABLE compensation_task
  ADD CONSTRAINT chk_compensation_task_status
  CHECK (status IN ('COMPLETED','FAILED','PENDING','PROCESSING'))
  NOT VALID;

ALTER TABLE repair_ticket
  ADD CONSTRAINT chk_repair_ticket_status
  CHECK (status IN ('CANCELLED','DONE','IN_PROGRESS','OPEN'))
  NOT VALID;

ALTER TABLE ops_exception
  ADD CONSTRAINT chk_ops_exception_status
  CHECK (status IN ('CLOSED','OPEN','PROCESSING','RESOLVED'))
  NOT VALID;

ALTER TABLE user_feedback
  ADD CONSTRAINT chk_user_feedback_status
  CHECK (status IN ('CLOSED','HANDLED','PENDING'))
  NOT VALID;
