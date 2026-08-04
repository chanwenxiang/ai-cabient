-- 东方树叶茉莉花茶 335ml — 阿里云类目 5839
INSERT INTO sku_catalog (sku_id, sku_name, price_cents)
VALUES ('SKU-TEA-5839', '东方树叶茉莉花茶335ml', 550)
ON CONFLICT (sku_id) DO UPDATE
    SET sku_name = EXCLUDED.sku_name,
        price_cents = EXCLUDED.price_cents;

INSERT INTO aliyun_category_mapping (category_id, category_name, sku_id, min_confidence)
VALUES ('5839', '东方树叶茉莉花茶335ml', 'SKU-TEA-5839', 0.35)
ON CONFLICT (category_id) DO UPDATE
    SET category_name = EXCLUDED.category_name,
        sku_id = EXCLUDED.sku_id,
        min_confidence = EXCLUDED.min_confidence;
