-- 设备模拟器默认识别商品，使用增量迁移避免修改已发布 Flyway 历史。
INSERT INTO sku_catalog (sku_id, sku_name, price_cents)
VALUES ('SKU-SODA-001', '汽水', 350),
       ('SKU-APPLE-001', '苹果', 500)
ON CONFLICT (sku_id) DO NOTHING;
