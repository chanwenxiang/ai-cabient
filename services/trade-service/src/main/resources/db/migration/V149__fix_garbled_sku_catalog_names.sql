-- Fix garbled demo SKU fields (literal '?' bytes from bad seed / encoding).
-- SKU-100009: category=饮料, barcode=6901420679189, price=¥3.99

UPDATE sku_catalog
SET sku_name = '冰红茶 500ml',
    brand = '康师傅',
    unit = '件',
    update_version = update_version + 1
WHERE sku_id = 'SKU-100009'
  AND (
    sku_name ~ '^\?+$'
    OR brand ~ '^\?+$'
    OR unit ~ '^\?+$'
  );

-- Soft-heal any other catalog rows whose name is only question marks.
UPDATE sku_catalog
SET sku_name = '演示商品-' || sku_id,
    unit = CASE WHEN unit ~ '^\?+$' OR unit IS NULL OR btrim(unit) = '' THEN '件' ELSE unit END,
    brand = CASE WHEN brand ~ '^\?+$' THEN NULL ELSE brand END,
    update_version = update_version + 1
WHERE sku_name ~ '^\?+$'
  AND sku_id <> 'SKU-100009';
