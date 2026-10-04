-- MIGRATION_KIND: backfill
-- 商品唯一性从「仅名称」改为「名称 + 规格」（规格空视为同一规格）
UPDATE sku_catalog SET spec = '' WHERE spec IS NULL;

ALTER TABLE sku_catalog DROP CONSTRAINT IF EXISTS uk_sku_name;
DROP INDEX IF EXISTS uk_sku_name;

WITH d AS (
    SELECT sku_id,
           ROW_NUMBER() OVER (
               PARTITION BY LOWER(TRIM(sku_name)), LOWER(TRIM(COALESCE(spec, '')))
               ORDER BY created_at NULLS LAST, sku_id
           ) AS rn
    FROM sku_catalog
)
UPDATE sku_catalog s
SET spec = CASE
    WHEN btrim(COALESCE(s.spec, '')) = '' THEN '重复-' || s.sku_id
    ELSE s.spec || '-重复-' || s.sku_id
END
FROM d
WHERE s.sku_id = d.sku_id
  AND d.rn > 1;

CREATE UNIQUE INDEX IF NOT EXISTS uk_sku_catalog_name_spec
    ON sku_catalog (LOWER(TRIM(sku_name)), LOWER(TRIM(COALESCE(spec, ''))));

COMMENT ON INDEX uk_sku_catalog_name_spec IS '同一商品名称+规格不可重复';
