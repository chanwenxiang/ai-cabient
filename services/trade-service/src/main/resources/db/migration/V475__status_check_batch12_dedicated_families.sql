-- MIGRATION_KIND: schema
-- COMPETITOR_REF: CB-036
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: medium（7 条 ADD CONSTRAINT ... CHECK ... NOT VALID：只取短暂 ACCESS EXCLUSIVE 且不扫存量行；VALIDATE 解耦为部署前脏值探针后的运维动作）
-- TABLES: shopping_session.upload_status / warehouse_outbound.handover_status / warehouse_outbound_line.handover_status / device_info.lifecycle_status / device_sku_lot.status / risk_event.disposition_status / sku_delist_review.review_status（第 12 批，②类转入：复用 6 个既有专用族，7 列）
-- NOTES: R20 P3-1 第 12 批（docs/AUDIT_FOUR_END_R20_2026-11-12.md §三十二）。第 9~11 批收敛完纯启用/停用池后，
--        本批起转入②类（需专用字典族列）。本批 7 列全部复用**既有**专用族，零新族（SysDictBootstrap <-> shared-dict 双端零改动）：
--          · shopping_session.upload_status          <- upload_status            {NONE,LOCAL_QUEUED,UPLOADING,UPLOADED,FAILED}
--          · warehouse_outbound.handover_status      <- handover_status          {PENDING,READY,IN_TRANSIT,PARTIAL,RECEIVED,CANCELLED}
--          · warehouse_outbound_line.handover_status <- handover_status          （同族）
--          · device_info.lifecycle_status            <- device_lifecycle         {IDLE,INBOUND,DEPLOYED,RETURNING,RETIRED}
--          · device_sku_lot.status                   <- inventory_lot_status     {ON_SALE,NEAR_EXPIRY,BLOCKED,DEPLETED}
--          · risk_event.disposition_status           <- risk_disposition_status  {OPEN,AUTO_CLEARED,ACKED}
--          · sku_delist_review.review_status         <- sku_review_status        {PENDING,RECOMMEND_DELIST,DELISTED,KEPT}
--        域的权威来源 = 全仓写入点穷举（六种写入形态逐一核实，含 setter 形态——R7 类锚点只覆盖 LambdaUpdateWrapper 形态）：
--          · upload_status：实体默认 "NONE"（ShoppingSession:42）；全仓零运行时写点（上传链已简化），族为既定值域超集，CHECK=族成立。
--          · handover_status：WarehouseService 930/934(READY) 983/988(IN_TRANSIT) 1028/1097/1105/1125(CANCELLED)
--            1298-1310(RECEIVED/PARTIAL)；无 mapper SQL 直写；DDL DEFAULT 'PENDING'(V34:61,64) ∈ 族。
--          · lifecycle_status：DeviceAssetService 状态机 LIFECYCLE=Set.of(INBOUND,IDLE,DEPLOYED,RETURNING,RETIRED)（:53）
--            与族键完全一致；写点 DemoDataService:280/DevicePresenceService:311/OpsDeviceAdminService:436,439/
--            transition.toStatus():115；DDL DEFAULT 'DEPLOYED'(V127:4) ∈ 族；DeviceInfoMapper 的 isNull 查询分支由
--            CHECK 三值逻辑天然放行。
--          · device_sku_lot.status：InventoryLotService 393/877(ON_SALE) 642(BLOCKED) 654/701(NEAR_EXPIRY)
--            resolveLotStatus:768-778 只返回 ON_SALE/NEAR_EXPIRY/BLOCKED；DDL DEFAULT 'ON_SALE'(V24:11) ∈ 族。
--          · disposition_status：RiskAutoDispositionService 119(AUTO_CLEARED) 139(ACKED)；DDL DEFAULT 'OPEN'(V191:3) ∈ 族。
--          · review_status：SkuDelistReviewService 154/164(PENDING) 233(DELISTED) 240(KEPT) 242(RECOMMEND_DELIST)；
--            DDL DEFAULT 'PENDING'(V163:167) ∈ 族。
--        🔴 门禁 R7 同批升级：键由实体类聚合改为「实体类名::getter 名」按列精确锚定——DeviceInfo（online_status/
--        lifecycle_status 双列）、WarehouseOutbound（status/handover_status 双列）若按类聚合会把 A 列写入值错拿去
--        对 B 列 CHECK（跨列污染，铁律 84② 判据贴真实责任边界）。41 列存量回归全绿。
--        登记排除（本批新增，不为减小数字硬造值域）：device_lifecycle_event.from_status/to_status——历史轨迹列，
--        同 repair_ticket_event.from/to 先例（事件快照需保留历史态迁移路径，现值约束会截断轨迹语义）。
--        🔴 对账勘误：jiangyi_class_mapping.status 自 excludedColumns 销案——dev 库历史迁移已建
--        ck_jiangyi_class_status {ACTIVE,DISABLED} 收口（ck_* 命名族不在本程序 CHECK 管控面，实测 2026-11-13）。
--        CHECK (col IN (...)) 对 NULL 天然放行（SQL 三值逻辑）；7 列均 NOT NULL，NULL 不构成风险。
--        部署前脏值探针（逐列，NULL 显式放行；<col> 为各表对应列名）：
--          SELECT DISTINCT <col> FROM <table> WHERE <col> IS NOT NULL AND UPPER(TRIM(<col>)) NOT IN (<族值集>);
--        零脏值后再按表执行 ALTER TABLE <table> VALIDATE CONSTRAINT chk_<约束名>;
--        VALIDATE 故意不写进迁移：大表全扫 + 存量脏值会让 Flyway 失败=部署失败。

ALTER TABLE shopping_session
  ADD CONSTRAINT chk_shopping_session_upload_status
  CHECK (upload_status IN ('NONE','LOCAL_QUEUED','UPLOADING','UPLOADED','FAILED'))
  NOT VALID;
ALTER TABLE warehouse_outbound
  ADD CONSTRAINT chk_warehouse_outbound_handover_status
  CHECK (handover_status IN ('PENDING','READY','IN_TRANSIT','PARTIAL','RECEIVED','CANCELLED'))
  NOT VALID;
ALTER TABLE warehouse_outbound_line
  ADD CONSTRAINT chk_warehouse_outbound_line_handover_status
  CHECK (handover_status IN ('PENDING','READY','IN_TRANSIT','PARTIAL','RECEIVED','CANCELLED'))
  NOT VALID;
ALTER TABLE device_info
  ADD CONSTRAINT chk_device_info_lifecycle_status
  CHECK (lifecycle_status IN ('IDLE','INBOUND','DEPLOYED','RETURNING','RETIRED'))
  NOT VALID;
ALTER TABLE device_sku_lot
  ADD CONSTRAINT chk_device_sku_lot_status
  CHECK (status IN ('ON_SALE','NEAR_EXPIRY','BLOCKED','DEPLETED'))
  NOT VALID;
ALTER TABLE risk_event
  ADD CONSTRAINT chk_risk_event_disposition_status
  CHECK (disposition_status IN ('OPEN','AUTO_CLEARED','ACKED'))
  NOT VALID;
ALTER TABLE sku_delist_review
  ADD CONSTRAINT chk_sku_delist_review_review_status
  CHECK (review_status IN ('PENDING','RECOMMEND_DELIST','DELISTED','KEPT'))
  NOT VALID;
