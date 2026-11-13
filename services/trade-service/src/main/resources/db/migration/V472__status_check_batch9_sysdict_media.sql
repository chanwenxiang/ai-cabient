-- MIGRATION_KIND: schema
-- COMPETITOR_REF: CB-036
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: medium（3 条 ADD CONSTRAINT ... CHECK ... NOT VALID：只取短暂 ACCESS EXCLUSIVE 且不扫存量行；VALIDATE 解耦为部署前脏值探针后的运维动作）
-- TABLES: sys_dict_data / sys_dict_type / media_asset（通用族 active_inactive_status 第 9 批，3 列）
-- NOTES: R20 P3-1 第 9 批（docs/AUDIT_FOUR_END_R20_2026-11-12.md §二十九）。
--        值域来自新增通用族 active_inactive_status = {ACTIVE,INACTIVE}（SysDictBootstrap <-> shared-dict 两端口字面相等，门禁跨端对齐通过）。
--        X 不能复用 enable_status：MA-146 一型两义 DRAFT/STOPPED 别名导致该族两端口键集不相等（后端 3 键 / 前端 5 键），门禁会红。
--        实证（源码真实写入点，非宽扫）：SysDictService:340 normalizeStatus() 强制 status in {ACTIVE,INACTIVE}；
--        MediaAssetService:157-158 白名单「status 仅支持 ACTIVE/INACTIVE」。DDL DEFAULT 均为 ACTIVE in 值域。
--        CHECK (col IN (...)) 对 NULL 天然放行（SQL 三值逻辑）。部署前脏值探针（逐列，NULL 显式放行）：
--          SELECT DISTINCT status FROM <table> WHERE status IS NOT NULL AND UPPER(TRIM(status)) NOT IN ('ACTIVE','INACTIVE');
--        零脏值后再按表执行 ALTER TABLE <table> VALIDATE CONSTRAINT chk_<table>_status;
--        VALIDATE 故意不写进迁移：大表全扫 + 存量脏值会让 Flyway 失败=部署失败。

ALTER TABLE sys_dict_data
  ADD CONSTRAINT chk_sys_dict_data_status
  CHECK (status IN ('ACTIVE','INACTIVE'))
  NOT VALID;
ALTER TABLE sys_dict_type
  ADD CONSTRAINT chk_sys_dict_type_status
  CHECK (status IN ('ACTIVE','INACTIVE'))
  NOT VALID;
ALTER TABLE media_asset
  ADD CONSTRAINT chk_media_asset_status
  CHECK (status IN ('ACTIVE','INACTIVE'))
  NOT VALID;
