-- MIGRATION_KIND: schema
-- COMPETITOR_REF: CB-036
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: medium（3 条 ADD CONSTRAINT ... CHECK ... NOT VALID：只取短暂 ACCESS EXCLUSIVE 且不扫存量行；VALIDATE 解耦为部署前脏值探针后的运维动作）
-- TABLES: ota_release.status / ota_device_report.upgrade_status / notification_log.status（第 14 批，②类建专用族，3 列 3 新族）
-- NOTES: R20 P3-1 第 14 批（docs/AUDIT_FOUR_END_R20_2026-11-12.md §三十四）。
--        域的权威来源 = 全仓写入点穷举（含 setter 形态，R7 类锚点只覆盖 LambdaUpdateWrapper 形态）：
--          · ota_release.status <- ota_release_status {DRAFT,PUBLISHED,UNPUBLISHED}：
--            OtaService 116(setStatus(STATUS_PUBLISHED)) 141(setStatus("UNPUBLISHED"))；实体默认 "DRAFT"(OtaRelease:34)；
--            mapper 仅 .eq 读。DDL DEFAULT 'DRAFT'(V9:19) ∈ 值域。
--          · ota_device_report.upgrade_status <- ota_upgrade_status {IDLE,DOWNLOADING,INSTALLING,SUCCESS,FAILED}：
--            设备上报链 OtaService.reportProgress → st=normalizeProgressStatus(status)（:281-288 对
--            ALLOWED_PROGRESS_STATUS=Set.of(IDLE,DOWNLOADING,INSTALLING,SUCCESS,FAILED) 白名单 **fail-closed 抛错**、
--            null 归一 IDLE、入参统一大写）→ doReportProgress 落库；OtaDeviceReportMapper:51 wrapper-set 传归一化后变量。
--            设备自由文本进不了库 ⇒ 域以服务端白名单为权威（DDL COMMENT V280:31 同口径）。DDL DEFAULT 'IDLE'(V280:25) ∈ 值域。
--          · notification_log.status <- notification_log_status {SENT}：
--            全仓 3 处硬编码写点恒 "SENT"（NotificationService:187/418、ExternalNotificationDispatcher:204，
--            注释明示「SENT=渠道已受理，不代表用户已收到」）+ 实体默认 "SENT"(NotificationLog:37)；零 FAILED 写点。
--            单值族成立：后续若引入 FAILED 必须经字典族+门禁同步演进，防静默新增状态。
--        X merchant.audit_status 本批登记排除（不入迁移）：全仓（main java）零写点——V68 遗留列、审核功能未实现，
--          无权威值域可实证（实证 PENDING 仅为 DDL 默认），不为无写点列硬造值域；留待功能实现时随功能定域。
--        CHECK (col IN (...)) 对 NULL 天然放行（SQL 三值逻辑）；3 列均 NOT NULL，NULL 不构成风险。
--        部署前脏值探针（逐列，NULL 显式放行）：
--          SELECT DISTINCT <col> FROM <table> WHERE <col> IS NOT NULL AND UPPER(TRIM(<col>)) NOT IN (<族值集>);
--        零脏值后再按表执行 ALTER TABLE <table> VALIDATE CONSTRAINT chk_<约束名>;
--        VALIDATE 故意不写进迁移：大表全扫 + 存量脏值会让 Flyway 失败=部署失败。

ALTER TABLE ota_release
  ADD CONSTRAINT chk_ota_release_status
  CHECK (status IN ('DRAFT','PUBLISHED','UNPUBLISHED'))
  NOT VALID;
ALTER TABLE ota_device_report
  ADD CONSTRAINT chk_ota_device_report_upgrade_status
  CHECK (upgrade_status IN ('IDLE','DOWNLOADING','INSTALLING','SUCCESS','FAILED'))
  NOT VALID;
ALTER TABLE notification_log
  ADD CONSTRAINT chk_notification_log_status
  CHECK (status IN ('SENT'))
  NOT VALID;
