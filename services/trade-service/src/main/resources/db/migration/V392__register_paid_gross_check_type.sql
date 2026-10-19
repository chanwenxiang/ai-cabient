-- MIGRATION_KIND: backfill
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- TABLES: sys_dict_data(约 4 千行，仅插 1 行)、sys_dict_type(只读存在性判断)
-- NOTES: 注册 G11 冻结毛额对账判据字典（cabinet_order.paid_amount_cents vs 流水入账和，
--        判据本体见 DataConsistencyService.checkPaidGrossConsistency，口径 V384/CB-057）。
--        与 V390 同姿势：纯 INSERT…WHERE NOT EXISTS ⇒ 幂等、不改任何既有行；运行期另有
--        SysDictBootstrap 同批登记标签，本迁移只负责让**历史已落的** data_consistency_record
--        行在字典页/前端拿到中文标签。判据「仅巡检」（改列=改佣金基数），前端不入 isFixable。

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status, remark)
SELECT v.dict_type, v.dict_value, v.dict_label, v.sort_order, 'ACTIVE', 'paid-gross-consistency-registry'
FROM (VALUES
    ('consistency_check_type', 'PAID_GROSS_MISMATCH', '冻结毛额入账', 103)
) AS v(dict_type, dict_value, dict_label, sort_order)
WHERE EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'consistency_check_type')
  AND NOT EXISTS (
    SELECT 1 FROM sys_dict_data d
    WHERE d.dict_type = v.dict_type AND d.dict_value = v.dict_value
  );
