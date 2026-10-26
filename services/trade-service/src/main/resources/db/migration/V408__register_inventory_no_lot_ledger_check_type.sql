-- MIGRATION_KIND: backfill
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- TABLES: sys_dict_data(约 5 百行，仅插 1 行)、sys_dict_type(只读存在性判断)
-- NOTES: 注册「无批次账本」巡检类型中文标签（B批 A 档；判据 DataConsistencyService
--        .checkInventoryNoLotLedgerConsistency）。背景：无 device_sku_lot 行的柜机被
--        INVENTORY_MISMATCH 的 EXISTS 谓词整体排除（2026-10-10 为消演示柜永久假阳性做的
--        口径修正），而货道账面只读批次表且无回退 ⇒ 「汇总有货 / 每个货道恒 0」这类形态
--        此前没有任何告警面。纯 INSERT…WHERE NOT EXISTS ⇒ 幂等、不改任何既有行；运行期另有
--        SysDictBootstrap 与 packages/shared-dict 同批登记标签（标签面三处同步的既有惯例，
--        见 V390），本迁移只负责让字典页与历史 FAIL 行拿到中文标签。

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status, remark)
SELECT v.dict_type, v.dict_value, v.dict_label, v.sort_order, 'ACTIVE', 'inventory-no-lot-ledger'
FROM (VALUES
    ('consistency_check_type', 'INVENTORY_NO_LOT_LEDGER', '无批次账本', 105)
) AS v(dict_type, dict_value, dict_label, sort_order)
WHERE EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'consistency_check_type')
  AND NOT EXISTS (
    SELECT 1 FROM sys_dict_data d
    WHERE d.dict_type = v.dict_type AND d.dict_value = v.dict_value
  );
