-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes（唯一键粒度修正；空表/重复行预检，重复则显式红）
-- COMPETITOR_REF: CB-059（费用台账按设备维度落明细行；设备级唯一性含 NULL 行）
-- B4 追加三（2026-11-16）：V487 的 UNIQUE(merchant_id, fee_date, fee_type, device_id)
-- 在 PostgreSQL 默认 NULLS DISTINCT 下，device_id IS NULL 的 RENT 行**不受唯一约束**
--（多行 NULL 互异）⇒ 数据库层只剩调度器预检，重跑/并发时可能落两行租金收据。
-- 本迁移改为 NULLS NOT DISTINCT（PG 15+），并先对存量重复行做显式预检。
--
-- 只影响 merchant_fee_ledger（费用台账=收据）；不改任何历史迁移。
-- LOCK_RISK: low（表行数 <100）
-- TABLES: merchant_fee_ledger

DO $$
DECLARE
    dupes text;
BEGIN
    SELECT string_agg(merchant_id || '|' || fee_date || '|' || fee_type, ', ' ORDER BY merchant_id)
      INTO dupes
      FROM (
        SELECT merchant_id, fee_date, fee_type
          FROM merchant_fee_ledger
         WHERE device_id IS NULL
         GROUP BY merchant_id, fee_date, fee_type
        HAVING count(*) > 1
      ) d;
    IF dupes IS NOT NULL THEN
        RAISE EXCEPTION 'merchant_fee_ledger 存在同商户/日/类型的 RENT 重复行，禁止建 NULLS NOT DISTINCT 唯一键：%', dupes
            USING HINT = '费用台账是资金收据：先逐行核对并人工合并重复行（禁止自动合并），再重跑本迁移';
    END IF;
END
$$;

ALTER TABLE merchant_fee_ledger DROP CONSTRAINT IF EXISTS uk_merchant_fee_date_type_device;

ALTER TABLE merchant_fee_ledger
    ADD CONSTRAINT uk_merchant_fee_date_type_device
    UNIQUE NULLS NOT DISTINCT (merchant_id, fee_date, fee_type, device_id);