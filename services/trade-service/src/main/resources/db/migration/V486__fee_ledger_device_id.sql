-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes（加可空列，瞬时完成，批 17 主审）
-- COMPETITOR_REF: CB-061
-- B4（R23 专项，2026-11-16 用户拍板）：merchant_fee_ledger 加 device 维度。
--
-- 成因：流量费在源头就是逐台计收（device_info.data_fee_cents 分/天，chargeDailyPeriodFees
-- 逐台累加），但写台账时聚合掉 device 维度 ⇒ 商户单设备视图流量费恒 0（MA-299）。
-- 加 device_id 后 DATA 类型按「商户×日×设备」逐台落行；RENT 保持商户级（合同是站点级，
-- 且已有 site_rent_bill 逐合同出账链路，不重复建维度）。
-- NULL = 商户级聚合行（历史行 + RENT 行），不回填拆分（历史日设备集合与费率快照无源数据，
-- 拆分是自创口径）。
-- LOCK_RISK: low（加可空列）
-- TABLES: merchant_fee_ledger
-- NOTES: DDL；幂等性由 Flyway 一次性执行保证；不改任何历史迁移

ALTER TABLE merchant_fee_ledger ADD COLUMN IF NOT EXISTS device_id VARCHAR(32) NULL;

COMMENT ON COLUMN merchant_fee_ledger.device_id IS
    'B4：费用归属设备（DATA 逐台行）；NULL=商户级聚合行（历史行/RENT 行）';
