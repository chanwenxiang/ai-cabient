-- MIGRATION_KIND: backfill
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- TABLES: sys_dict_data(约 4 千行，仅插 5 行)、sys_dict_type(只读存在性判断)
-- NOTES: 注册三域钱包巡检类型字典（V388 统一后判据按 domain 生成，补货员域与两条 Σ/链判据此前无中文标签）。
--        背景：MERCHANT/LINE_WALLET_LEDGER_SUM 自 P3-1a 起就在写 data_consistency_record，但从未进字典 ⇒
--        后台一律显示「未知类型」；STAFF 域钱包当时完全没有对账判据，本轮泛化后新增。
--        纯 INSERT…WHERE NOT EXISTS ⇒ 幂等、不改任何既有行；运行期另有 SysDictBootstrap 同批登记标签，
--        本迁移只负责让**历史已落的** data_consistency_record 行在字典页/前端拿到中文标签。
--        已应用本迁移的环境升级头注释后需 `flyway repair` 对齐 checksum（本仓惯例，见 V244/V252）。

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status, remark)
SELECT v.dict_type, v.dict_value, v.dict_label, v.sort_order, 'ACTIVE', 'wallet-consistency-registry'
FROM (VALUES
    ('consistency_check_type', 'STAFF_WALLET', '补货员钱包', 122),
    ('consistency_check_type', 'MERCHANT_WALLET_LEDGER_SUM', '商户钱包流水合计', 124),
    ('consistency_check_type', 'LINE_WALLET_LEDGER_SUM', '线长钱包流水合计', 132),
    ('consistency_check_type', 'STAFF_WALLET_LEDGER_SUM', '补货员钱包流水合计', 134),
    ('consistency_check_type', 'WALLET_LEDGER_CHAIN', '钱包流水链断裂', 136)
) AS v(dict_type, dict_value, dict_label, sort_order)
WHERE EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'consistency_check_type')
  AND NOT EXISTS (
    SELECT 1 FROM sys_dict_data d
    WHERE d.dict_type = v.dict_type AND d.dict_value = v.dict_value
  );
