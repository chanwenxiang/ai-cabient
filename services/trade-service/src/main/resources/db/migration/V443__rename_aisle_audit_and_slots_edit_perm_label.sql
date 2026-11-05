-- MIGRATION_KIND: backfill
-- 批 3b 第九刀：把生产界面仅剩的两处「货道」名字改成它实际在说的话。
--   1) 字典 device_ops_event.AISLE_AUDIT：现名「货道巡检」，但该事件由**开门柜盘点**写入
--      （MerchantDevicePortalService 的 submitDoorStocktake 里 setEventType("AISLE_AUDIT")），
--      商户端「理货记录-盘点」tab 读的就是它 ⇒ 码不能退役，只改名「盘点巡检」。
--   2) ops_permission 226 `merchant:slots:edit` 的 perm_name：现名「货道编辑」，但这个码
--      现在门的是 /stocktake-door、/door-stocktake、盘点凭证上传三处
--      （MerchantPortalController:167/176/212）⇒ 改「柜机盘点（含凭证）」。
--      同段 225 `merchant:slots:view` 已随 V442 置 RETIRED（只在 includeInactive 里可见），不动它的名字。
-- 三源一致（V438 姿势）：packages/shared-dict/src/index.ts 与 SysDictBootstrap 已同批改；
--   迁移负责存量行，bootstrap 负责「库里缺行时」的新插入标签 ⇒ 不会出现库/代码两套译名。
-- 幂等：两条 UPDATE 都带「当前值 <> 目标值」条件；无 DDL，不动权限码与授权关系。
-- ROLLBACK: UPDATE sys_dict_data SET dict_label = '货道巡检'
--             WHERE dict_type = 'device_ops_event' AND dict_value = 'AISLE_AUDIT';
--           UPDATE ops_permission SET perm_name = '货道编辑'
--             WHERE perm_code = 'merchant:slots:edit';
-- LOCK_RISK: low
-- TABLES: sys_dict_data（≤1 行）、ops_permission（1 行）
-- NOTES: 纯 DML 改标签
UPDATE sys_dict_data
   SET dict_label = '盘点巡检'
 WHERE dict_type = 'device_ops_event'
   AND dict_value = 'AISLE_AUDIT'
   AND dict_label <> '盘点巡检';

UPDATE ops_permission
   SET perm_name = '柜机盘点（含凭证）'
 WHERE perm_code = 'merchant:slots:edit'
   AND perm_name <> '柜机盘点（含凭证）';
