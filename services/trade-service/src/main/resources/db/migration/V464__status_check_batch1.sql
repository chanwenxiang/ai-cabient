-- MIGRATION_KIND: schema
-- COMPETITOR_REF: CB-086,CB-036
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: medium（三条 ADD CONSTRAINT ... NOT VALID 只取短暂 ACCESS EXCLUSIVE 且**不扫存量行**；
--            不带 NOT VALID 会全表扫并持锁，核心老表禁止。VALIDATE CONSTRAINT 需按表量级评估后单独执行）
-- TABLES: cabinet_order / recharge_order / dispute_ticket（本批 3 列；dev 实测 87 列缺 CHECK，余 84 列为显式欠账）
-- NOTES: R20 P3-1（docs/AUDIT_FOUR_END_R20_2026-11-12.md §二十三 蓝图 / §二十六 回执）。
--        值域来自 SysDictBootstrap 字典族 order_status / recharge_status / dispute_status，
--        并由 check-status-enum-parity.mjs 与跨端 shared-dict 自动对齐（新增状态未同步约束 ⇒ CI 红）。
--        🔴 禁止用 dev 稀疏值域推枚举：dev 100 个状态列仅 13 个有 CHECK、全库 distinct 仅 113 行，
--        cabinet_order.status 源码写入 7 值而 dev 只出现 3 值 ⇒ 采样生成的约束上线即写入失败。
--        CB-036 边界：sku_catalog.operation_scope 等可扩展字段继续走字典零迁移，本批不碰。
--        CHECK (col IN (...)) 对 NULL 天然放行（SQL 三值逻辑，CHECK 只在 FALSE 时拒绝），无需显式 IS NULL OR。
--        生产上线前探针（逐列，NULL 显式放行）：
--          SELECT DISTINCT <col> FROM <table>
--           WHERE <col> IS NOT NULL AND UPPER(TRIM(<col>)) NOT IN (<同一字面量集合>);
--        有脏值先出清理工单；确认零脏值后再按表执行
--          ALTER TABLE <table> VALIDATE CONSTRAINT <constraint>;
--        ⚠️ VALIDATE 故意不写进本迁移：大表全扫 + 存量脏值会让 Flyway 迁移失败＝部署失败，
--        必须与「加约束」解耦为运维动作（见报告 §二十六）。

ALTER TABLE cabinet_order
  ADD CONSTRAINT chk_cabinet_order_status
  CHECK (status IN ('PENDING','PROCESSING','PAID','COMPLETED','DISPUTED','REFUNDED','PARTIAL_REFUNDED','FAILED','CANCELLED'))
  NOT VALID;

ALTER TABLE recharge_order
  ADD CONSTRAINT chk_recharge_order_status
  CHECK (status IN ('CREATED','PENDING','PAID','SUCCESS','FAILED','REFUNDED','CANCELLED','CLOSED'))
  NOT VALID;

ALTER TABLE dispute_ticket
  ADD CONSTRAINT chk_dispute_ticket_status
  CHECK (status IN ('OPEN','RESOLVED','CLOSED'))
  NOT VALID;
