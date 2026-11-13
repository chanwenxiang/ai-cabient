-- MIGRATION_KIND: schema
-- COMPETITOR_REF: CB-036
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: medium（4 条 ADD CONSTRAINT ... CHECK ... NOT VALID：只取短暂 ACCESS EXCLUSIVE 且不扫存量行；VALIDATE 解耦为部署前脏值探针后的运维动作）
-- TABLES: line_route.status / line_route_member.status / line_device.status / warehouse_inbound.status（第 15 批，②类线路链+入库单，4 列 3 新族 + 复用通用族）
-- NOTES: R20 P3-1 第 15 批（docs/AUDIT_FOUR_END_R20_2026-11-12.md §三十五）。
--        🔴 背景陷阱：line_manager/line_device/line_route 均为 V129 DROP → V133 重建、line_route_member 为 V358
--        重建的新模型（S14 曾被 DROP→重建 序列骗过误判死语句）；本批约束针对**重建后**的现役表（dev to_regclass 实测均在）。
--        域的权威来源 = 全仓写入点穷举（含 setter 形态，R7 类锚点只覆盖 LambdaUpdateWrapper 形态）：
--          · line_route.status <- line_route_status {ACTIVE,ARCHIVED}：
--            OpsLineRouteController:47 新建置 ACTIVE；LineRouteAdminService:138 归档置 ARCHIVED（可逆，update 不触碰 status）；
--            实体无 Java 默认；DDL DEFAULT 'ACTIVE'(V133:74) ∈ 值域。
--          · line_route_member.status <- line_route_member_status {ACTIVE}：
--            LineRouteAdminService 182(更新成员复活) 193(新建) 恒置 ACTIVE，无停用/删除写点（成员移除走 DELETE 端点物理删）；
--            DDL DEFAULT 'ACTIVE'(V358:25) ∈ 值域。单值族防静默新增状态。
--          · line_device.status <- 复用 active_inactive_status {ACTIVE,INACTIVE}：
--            LineManagerService 265(bindDevice 置 STATUS_ACTIVE="ACTIVE") 287(unbindDevice 置 "INACTIVE")；
--            DDL DEFAULT 'ACTIVE'(V133:62) ∈ 值域；uk_line_device_active 部分唯一索引 WHERE status='ACTIVE' 与域协同。
--            ⚠️ 读侧遗留：LineWithdrawService:580 容忍读 status='BOUND'——V129 前旧 line_device 的死值（现模型无写点），
--            CHECK 不影响读（BOUND 行不存在，读恒空同今日），登记为观察项不在本批修。
--          · warehouse_inbound.status <- warehouse_inbound_status {COMPLETED}：
--            全仓零运行时写点——WarehouseService 242/280 两处创建均不 setStatus，恒走 DDL DEFAULT 'COMPLETED'(V26:29)；
--            单值族成立（入库单即完成态，无草稿/取消流）。
--        同名单词跨实体甄别（防污染）：ReplenishmentService/MerchantReplenishmentService 的 route.setStatus 是
--        replenishment_route（第 3 批已收口）；JiangyiOnboardingService 的 device.setStatus(UNBOUND/BOUND/RETIRED)
--        是 jiangyi_device（历史 ck_jiangyi_device_status 已收口）——均非本批 line_* 实体。
--        dev 库 4 表现均 0 行（V133/V358 新模型未投产），域纯以源码写点定域（探针零行不代表无约束价值）。
--        CHECK (col IN (...)) 对 NULL 天然放行（SQL 三值逻辑）；4 列均 NOT NULL，NULL 不构成风险。
--        部署前脏值探针（逐列，NULL 显式放行）：
--          SELECT DISTINCT <col> FROM <table> WHERE <col> IS NOT NULL AND UPPER(TRIM(<col>)) NOT IN (<族值集>);
--        零脏值后再按表执行 ALTER TABLE <table> VALIDATE CONSTRAINT chk_<约束名>;
--        VALIDATE 故意不写进迁移：大表全扫 + 存量脏值会让 Flyway 失败=部署失败。

ALTER TABLE line_route
  ADD CONSTRAINT chk_line_route_status
  CHECK (status IN ('ACTIVE','ARCHIVED'))
  NOT VALID;
ALTER TABLE line_route_member
  ADD CONSTRAINT chk_line_route_member_status
  CHECK (status IN ('ACTIVE'))
  NOT VALID;
ALTER TABLE line_device
  ADD CONSTRAINT chk_line_device_status
  CHECK (status IN ('ACTIVE','INACTIVE'))
  NOT VALID;
ALTER TABLE warehouse_inbound
  ADD CONSTRAINT chk_warehouse_inbound_status
  CHECK (status IN ('COMPLETED'))
  NOT VALID;
