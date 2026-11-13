-- MIGRATION_KIND: schema
-- COMPETITOR_REF: CB-036
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: medium（1 条 ADD CONSTRAINT ... CHECK ... NOT VALID：只取短暂 ACCESS EXCLUSIVE 且不扫存量行；VALIDATE 解耦为部署前脏值探针后的运维动作）
-- TABLES: user_info（专用族 user_account_status 第 11 批，1 列三值）
-- NOTES: R20 P3-1 第 11 批（docs/AUDIT_FOUR_END_R20_2026-11-12.md §三十一）。第 10 批（V473）实证 user_info.status
--        真域为 {ACTIVE,INACTIVE,DELETED} 三值，不入 active_inactive_status 两值通用族，本批以专用族收口。
--        值域来自专用字典族 user_account_status = {ACTIVE,INACTIVE,DELETED}（SysDictBootstrap <-> shared-dict
--        两端口字面相等，门禁跨端对齐通过）。
--        域的权威来源 = 全仓写入点穷举（非宽扫，六种写入形态逐一核实）：
--          · ACTIVE：OpsAdminBootstrapService:92 / MerchantTeamAdminService:258（实体字段默认 "ACTIVE"）/
--            OpsRbacService.normalizeAccountStatus:1046-1055（管理端点白名单，仅放行 ACTIVE/INACTIVE，越界 400）/
--            V96 DDL DEFAULT 'ACTIVE' + 回填 UPDATE status='ACTIVE'；
--          · INACTIVE：OpsRbacService:524 disableOperator（运营封禁；AuthService:402 注释 INACTIVE=运营封禁）；
--          · DELETED：AccountService:158 set(UserInfo::getStatus,"DELETED")（AU-149 账号注销匿名化，
--            AuthService:402 注释 DELETED=已注销、登录闸门一律拒绝）。
--          其余 11 处 UPDATE user_info 迁移只碰 password_hash/wx_open_id/name 等列，不触碰 status；
--          种子数据不写 user_info.status；dev 库 DISTINCT(status) 实测仅 ACTIVE（无已注销账户，与源码实证互补）。
--        X 管理端点不可能写入 DELETED：normalizeAccountStatus 白名单仅 ACTIVE/INACTIVE，注销只走 AccountService 自助闭环。
--        DDL DEFAULT 'ACTIVE' ∈ 值域（R6 静态可判合规，默认插入不会被拒；该列由 V96 ALTER TABLE ADD COLUMN 追加）。
--        CHECK (col IN (...)) 对 NULL 天然放行（SQL 三值逻辑）；列 NOT NULL，NULL 不构成风险。
--        部署前脏值探针（NULL 显式放行）：
--          SELECT DISTINCT status FROM user_info WHERE status IS NOT NULL AND UPPER(TRIM(status)) NOT IN ('ACTIVE','INACTIVE','DELETED');
--        零脏值后再执行 ALTER TABLE user_info VALIDATE CONSTRAINT chk_user_info_status;
--        VALIDATE 故意不写进迁移：大表全扫 + 存量脏值会让 Flyway 失败=部署失败。

ALTER TABLE user_info
  ADD CONSTRAINT chk_user_info_status
  CHECK (status IN ('ACTIVE','INACTIVE','DELETED'))
  NOT VALID;
