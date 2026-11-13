-- MIGRATION_KIND: schema
-- COMPETITOR_REF: CB-036
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: medium（7 条 ADD CONSTRAINT ... CHECK ... NOT VALID：只取短暂 ACCESS EXCLUSIVE 且不扫存量行；VALIDATE 解耦为部署前脏值探针后的运维动作）
-- TABLES: invoice_request.status / external_import_batch.status / device_rental_deposit.status / device_mqtt_credential.status / pull_off_task.status / shopping_session.preauth_status / purchase_order_line.quality_status（第 16 批，②类杂项尾批，7 列 7 新族）
-- NOTES: R20 P3-1 第 16 批（docs/AUDIT_FOUR_END_R20_2026-11-12.md §三十六）。87 列基线的收口批——本批后
--        棘轮余量全部为登记排除/未部署建表列，无未分类悬空列。
--        域的权威来源 = 全仓写入点穷举（含 setter 形态，R7 类锚点只覆盖 LambdaUpdateWrapper 形态）：
--          · invoice_request.status <- invoice_request_status {PENDING,ISSUED,REJECTED}：
--            InvoiceService 101(创建 STATUS_PENDING="PENDING") 141(ISSUED) 161(REJECTED)；mapper 仅 .eq/.in 读；
--            DDL DEFAULT 'PENDING'(V190:10) ∈ 值域。
--          · external_import_batch.status <- external_import_status {PARSED,COMMITTED}：
--            ExternalChannelImportService 147(创建解析 PARSED) 427(COMMITTED)；实体常量仅此两值(:19-20)；
--            DDL DEFAULT 'PARSED'(V347:65，行注释 PARSED/COMMITTED 同口径) ∈ 值域。
--          · device_rental_deposit.status <- device_rental_deposit_status {HELD,SETTLED}：
--            DeviceRentalService 304/388(结算 SETTLED)；创建(:164)不 setStatus 走 DDL DEFAULT 'HELD'(V342:24)；实体常量 HELD/SETTLED(:17-18)。
--          · device_mqtt_credential.status <- device_mqtt_credential_status {ACTIVE,REVOKED}：
--            DeviceMqttCredentialService 78(签发 ACTIVE) 95(吊销 REVOKED)，常量 :36-37；DDL DEFAULT 'ACTIVE'(V290:14) ∈ 值域。
--          · pull_off_task.status <- pull_off_task_status {OPEN}：
--            InventoryLotService 671/704 两处创建均不 setStatus，全仓零终结写点（无 DONE/CANCEL 状态机），
--            恒走 DDL DEFAULT 'OPEN'(V24:66)；单值族防静默新增状态。
--          · shopping_session.preauth_status <- preauth_status {NONE,FROZEN,CAPTURED,RELEASED}：
--            ConsumerPreauthService 常量 :34-37 + 写点 142/160/183/204/246/283/305/384（冻结/释放/扣款/清零全生命周期）；
--            实体默认 "NONE"(ShoppingSession:69)；DDL DEFAULT 'NONE'(V141:9) 且 :13 DDL COMMENT 同口径 ∈ 值域。
--          · purchase_order_line.quality_status <- quality_status {PENDING,PASSED}：
--            实体默认 "PENDING"(PurchaseOrderLine:30) + ProcurementService:538 验收 PASSED；
--            DDL DEFAULT 'PENDING'(V34:57 ALTER ADD COLUMN) ∈ 值域。
--        🔴 user_realname_auth.auth_status 本批登记排除（不入迁移）：**设计性恒 0 行**——
--        WithdrawEligibilityService:24 注释明示「该表在本设计下永远是 0 行，不是缺陷」，全仓无实体/无写点，
--        值域无从实证；若未来启用实名落库须先建实体+族再随批收口。
--        同名单词甄别：ConsumerPreauthService 的 session 均为 ShoppingSession（preauth_status 唯一写点归宿）；
--        DeviceRentalService 450/469 的 bill.setStatus 是 device_data_fee_bill（历史 ck 已收口），非本批 deposit。
--        CHECK (col IN (...)) 对 NULL 天然放行（SQL 三值逻辑）；7 列均 NOT NULL，NULL 不构成风险。
--        部署前脏值探针（逐列，NULL 显式放行）：
--          SELECT DISTINCT <col> FROM <table> WHERE <col> IS NOT NULL AND UPPER(TRIM(<col>)) NOT IN (<族值集>);
--        零脏值后再按表执行 ALTER TABLE <table> VALIDATE CONSTRAINT chk_<约束名>;
--        VALIDATE 故意不写进迁移：大表全扫 + 存量脏值会让 Flyway 失败=部署失败。

ALTER TABLE invoice_request
  ADD CONSTRAINT chk_invoice_request_status
  CHECK (status IN ('PENDING','ISSUED','REJECTED'))
  NOT VALID;
ALTER TABLE external_import_batch
  ADD CONSTRAINT chk_external_import_batch_status
  CHECK (status IN ('PARSED','COMMITTED'))
  NOT VALID;
ALTER TABLE device_rental_deposit
  ADD CONSTRAINT chk_device_rental_deposit_status
  CHECK (status IN ('HELD','SETTLED'))
  NOT VALID;
ALTER TABLE device_mqtt_credential
  ADD CONSTRAINT chk_device_mqtt_credential_status
  CHECK (status IN ('ACTIVE','REVOKED'))
  NOT VALID;
ALTER TABLE pull_off_task
  ADD CONSTRAINT chk_pull_off_task_status
  CHECK (status IN ('OPEN','RESOLVED'))
  NOT VALID;
ALTER TABLE shopping_session
  ADD CONSTRAINT chk_shopping_session_preauth_status
  CHECK (preauth_status IN ('NONE','FROZEN','CAPTURED','RELEASED'))
  NOT VALID;
ALTER TABLE purchase_order_line
  ADD CONSTRAINT chk_purchase_order_line_quality_status
  CHECK (quality_status IN ('PENDING','PASSED'))
  NOT VALID;
