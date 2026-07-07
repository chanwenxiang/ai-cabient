-- 从旧库 ego-automat (MySQL) 导出 SKU
-- 旧 sku_id 为整数，新系统建议使用 SKU-{id} 或保持字符串化 id

SELECT
    CONCAT('SKU-', g.sku_id) AS sku_id,
    CONCAT(
        COALESCE(g.sku_package_type, ''),
        COALESCE(g.sku_subject, ''),
        COALESCE(g.sku_size, '')
    ) AS sku_name,
    COALESCE(g.sku_selling_price, g.sku_original_price, 0) AS price_cents,
    g.sku_weight AS weight_grams
FROM ego_goods_sku_info g
WHERE g.sku_is_delete = 0
  AND (g.status IS NULL OR g.status = 1)
ORDER BY g.sku_id;
