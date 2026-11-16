-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes（仅 DROP 冗余唯一索引，批 17 主审）
-- COMPETITOR_REF: CB-059（同 V486/V487）
-- B4 追加二（V487 真库验证抓到）：uk_merchant_fee_date_type 在历史库中以
-- CREATE UNIQUE INDEX（独立索引）形态存在而非 TABLE CONSTRAINT —— V487 的
-- DROP CONSTRAINT IF EXISTS 只删掉了约束对象，同名唯一索引仍在 ⇒ 逐台行照样撞键。
-- 本迁移补 DROP INDEX；新约束 uk_merchant_fee_date_type_device（V487）已生效。
-- 教训（入台账）：约束迁移必须先查 pg_indexes + pg_constraint 双处，IF EXISTS 按
-- 实际形态写（DROP CONSTRAINT 对独立索引是 no-op 且不报错）。
-- LOCK_RISK: low
-- TABLES: merchant_fee_ledger
-- NOTES: DDL；不改任何历史迁移

DROP INDEX IF EXISTS uk_merchant_fee_date_type;
