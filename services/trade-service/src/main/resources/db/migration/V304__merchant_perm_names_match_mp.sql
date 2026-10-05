-- MIGRATION_KIND: backfill
-- TABLES: ops_permission（权限目录，行数固定几十）；merchant（仅 COMMENT）
-- LOCK_RISK: low
-- ROLLBACK: 再写迁移改回旧 perm_name；权限码未改
-- NOTES: 只改商户权限展示名，对齐小程序文案；不改 perm_code、不挪 parent/功能包。
-- 采购入库与补货任务共用 merchant:replenishment:view（无独立码）。
-- 要货申请入口可见用 view，提交仍要 F 级 merchant:replenishment:request。

-- 现场作业（小程序工作台 + 「我的」里的仓配入口）
UPDATE ops_permission SET perm_name = '柜机管理' WHERE perm_code = 'merchant:devices:list';
UPDATE ops_permission SET perm_name = '补货任务' WHERE perm_code = 'merchant:replenishment:view';
UPDATE ops_permission SET perm_name = '要货申请' WHERE perm_code = 'merchant:replenishment:request';
UPDATE ops_permission SET perm_name = '待办事项' WHERE perm_code = 'merchant:alerts:view';

-- 经营工具（小程序「我的」：点位定价/结算/争议/经营分析；钱包订单仍在经营包）
UPDATE ops_permission SET perm_name = '柜机订单' WHERE perm_code = 'merchant:orders:list';
UPDATE ops_permission SET perm_name = '点位定价' WHERE perm_code = 'merchant:pricing:view';
UPDATE ops_permission SET perm_name = '修改点位定价' WHERE perm_code = 'merchant:pricing:edit';
UPDATE ops_permission SET perm_name = '争议处理' WHERE perm_code = 'merchant:disputes:list';
UPDATE ops_permission SET perm_name = '销售报表' WHERE perm_code = 'merchant:reports:view';

COMMENT ON COLUMN merchant.pack_field_enabled IS '功能包：现场作业（柜机/补货任务/待办；采购入库与要货入口同补货任务权）';
COMMENT ON COLUMN merchant.pack_biz_enabled IS '功能包：经营工具（订单钱包；点位定价/结算/争议/经营分析入口在小程序「我的」）';
COMMENT ON COLUMN merchant.pack_team_enabled IS '功能包：团队与设置（商户设置/团队成员）';
