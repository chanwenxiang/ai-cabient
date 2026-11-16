-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes（改唯一约束粒度，批 17 主审）
-- COMPETITOR_REF: CB-061
-- B4 追加（V486 真库验证抓到的缺口）：merchant_fee_ledger 原唯一约束
-- uk_merchant_fee_date_type(merchant_id, fee_date, fee_type) 只允许每 (商户,日,类型) 一行
-- —— 与 B4 逐台落行冲突（第二台设备的 DATA 行必撞键）。
--
-- 修法：约束扩到 (merchant_id, fee_date, fee_type, device_id)。NULLS DISTINCT（PG 默认）
-- 下旧行 (…, NULL) 互异 ⇒ 历史聚合行不受影响；新逐台行 (…, device_id) 各自唯一。
-- 写入侧幂等查询已按同粒度实现（upsertFeeLedger），约束是最后一道防线。
-- LOCK_RISK: low（约束重建瞬时完成，dev 台账行数 <100）
-- TABLES: merchant_fee_ledger
-- NOTES: DDL；不改任何历史迁移

ALTER TABLE merchant_fee_ledger DROP CONSTRAINT IF EXISTS uk_merchant_fee_date_type;

ALTER TABLE merchant_fee_ledger
    ADD CONSTRAINT uk_merchant_fee_date_type_device
    UNIQUE (merchant_id, fee_date, fee_type, device_id);
