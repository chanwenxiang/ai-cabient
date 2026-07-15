-- 补齐视觉映射引用的 SKU（V7 依赖 sku_catalog 外键）
INSERT INTO sku_catalog (sku_id, sku_name, price_cents)
VALUES ('SKU-SODA-001', '汽水', 350),
       ('SKU-APPLE-001', '苹果', 500)
ON CONFLICT (sku_id) DO NOTHING;
