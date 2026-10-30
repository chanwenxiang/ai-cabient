-- MIGRATION_KIND: backfill
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- TABLES: shopping_session（仅 UPDATE device_name 空值行；判据 = device_name IS NULL OR ''，
--        行数 = 历史补货/盘点/运维开门会话数，dev 实测个位数~两位数量级）
-- NOTES: 2026-10-30 缺陷修复配套回填——applySessionDeviceName（写入时固化设备名快照）此前
--        只挂在消费者开门路径（SessionOpenService/SessionService），补货开门（OpsService）与
--        盘点开门（MerchantDevicePortalService）落库不回填 → 会话列表/设备关联记录「设备」列
--        只能回退显示设备编号（EG943322621391 这类），与消费者会话显示名称口径不一致。
--        写入侧已修（同日起 save 前回填）；本迁移只对**快照缺失的存量行**按 device_info 现名补空，
--        不覆盖任何已有值——与 V239/V240「仅补空」先例同规。快照一经写入禁止读路径改写的设计不变。
--        幂等：重复执行时快照已非空，WHERE 条件不再命中，天然 no-op。

UPDATE shopping_session s
SET device_name = d.device_name
FROM device_info d
WHERE s.device_id = d.device_id
  AND (s.device_name IS NULL OR s.device_name = '');
