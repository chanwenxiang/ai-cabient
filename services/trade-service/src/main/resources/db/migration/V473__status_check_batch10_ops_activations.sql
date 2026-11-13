-- MIGRATION_KIND: schema
-- COMPETITOR_REF: CB-036
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: medium（7 条 ADD CONSTRAINT ... CHECK ... NOT VALID：只取短暂 ACCESS EXCLUSIVE 且不扫存量行；VALIDATE 解耦为部署前脏值探针后的运维动作）
-- TABLES: ops_department / sku_catalog / member_level_rule / points_redeem_item / coupon_definition / ops_role / platform_fund_account（通用族 active_inactive_status 第 10 批，7 列纯启用/停用）
-- NOTES: R20 P3-1 第 10 批（docs/AUDIT_FOUR_END_R20_2026-11-12.md §三十）。
--        继续复用第 9 批引入的通用族 active_inactive_status = {ACTIVE,INACTIVE}（SysDictBootstrap <-> shared-dict 两端口字面相等，门禁跨端对齐通过）。
--        X 不能复用 enable_status：MA-146 一型两义 DRAFT/STOPPED 别名导致该族两端口键集不相等（后端 3 键 / 前端 5 键），门禁会红。
--        实证（源码真实写入点，非宽扫）：7 张表均无后端硬编码 status 字面量（值由 admin-vue 前端下拉传入、后端只持久化），
--        故域的权威来源采用「前端 UI 选项 + 实体默认 + dev 实证去重值」三方交叉：
--          · ops_department / sku_catalog / member_level_rule / points_redeem_item / coupon_definition / ops_role / platform_fund_account
--            在 dev 库 DISTINCT(status) 去重值均 ⊆ {ACTIVE,INACTIVE}；
--          · 实体默认：各实体 setStatus 默认 ACTIVE / 注释明示「启用/停用」；
--          · 前端：DepartmentManageView 批量启用/停用 + ACTIVE/INACTIVE 下拉、OperatorManageView 正常/停用 radio、
--            MemberLevelsView / PointsRedeemView / CouponsView / PlatformFundView 均有 INACTIVE/启用/停用。
--        DDL DEFAULT 全部为 'ACTIVE' ∈ {ACTIVE,INACTIVE}（R6 静态可判合规，默认插入不会被拒）：
--          ops_department(V231:8) / sku_catalog(V18 ALTER ADD COLUMN DEFAULT 'ACTIVE') / member_level_rule(V79:55,V99:50)
--          / points_redeem_item(V99:74) / coupon_definition(V66:41) / ops_role(V8:7) / platform_fund_account(V341:17)。
--        platform_fund_account 本批从 excludedColumns 解停泊：其实体无 DISABLED 常量、UI 走 enable_status 族映射、
--        域确为 {ACTIVE,INACTIVE}，符合通用族。
--        登记排除（域非 {ACTIVE,INACTIVE}，不为减小数字硬造值域）：
--          user_info(源码 AccountService:158 写 set(UserInfo::getStatus,"DELETED") 账号匿名化 + AuthService 注释 INACTIVE=运营封禁，
--            域为 {ACTIVE,INACTIVE,DELETED}，dev 实证漏采 deleted 账户故只显示 ACTIVE —— 须用专用 3 值族收口，不在本通用族)；
--          ops_permission(dev 实测含 RETIRED) / jiangyi_class_mapping(dev 实测含 DISABLED) / notification_template(前端无停用入口)。
--        sku_catalog / user_info 的 status 由 ALTER TABLE ADD COLUMN ... DEFAULT 'ACTIVE' 追加（非 CREATE TABLE），
--        门禁 collectDdlDefaults 已扩展至扫描 ALTER 加列默认值以覆盖此形态。
--        CHECK (col IN (...)) 对 NULL 天然放行（SQL 三值逻辑）；8 列均 NOT NULL，NULL 不构成风险。
--        部署前脏值探针（逐列，NULL 显式放行）：
--          SELECT DISTINCT status FROM <table> WHERE status IS NOT NULL AND UPPER(TRIM(status)) NOT IN ('ACTIVE','INACTIVE');
--        零脏值后再按表执行 ALTER TABLE <table> VALIDATE CONSTRAINT chk_<table>_status;
--        VALIDATE 故意不写进迁移：大表全扫 + 存量脏值会让 Flyway 失败=部署失败。

ALTER TABLE ops_department
  ADD CONSTRAINT chk_ops_department_status
  CHECK (status IN ('ACTIVE','INACTIVE'))
  NOT VALID;
ALTER TABLE sku_catalog
  ADD CONSTRAINT chk_sku_catalog_status
  CHECK (status IN ('ACTIVE','INACTIVE'))
  NOT VALID;
ALTER TABLE member_level_rule
  ADD CONSTRAINT chk_member_level_rule_status
  CHECK (status IN ('ACTIVE','INACTIVE'))
  NOT VALID;
ALTER TABLE points_redeem_item
  ADD CONSTRAINT chk_points_redeem_item_status
  CHECK (status IN ('ACTIVE','INACTIVE'))
  NOT VALID;
ALTER TABLE coupon_definition
  ADD CONSTRAINT chk_coupon_definition_status
  CHECK (status IN ('ACTIVE','INACTIVE'))
  NOT VALID;
ALTER TABLE ops_role
  ADD CONSTRAINT chk_ops_role_status
  CHECK (status IN ('ACTIVE','INACTIVE'))
  NOT VALID;
ALTER TABLE platform_fund_account
  ADD CONSTRAINT chk_platform_fund_account_status
  CHECK (status IN ('ACTIVE','INACTIVE'))
  NOT VALID;
