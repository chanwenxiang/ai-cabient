-- 演示商品目录：多 SKU、分类、图片、保质期；柜内库存；默认仓库批次

INSERT INTO sku_catalog (sku_id, sku_name, price_cents, weight_grams, vision_enabled, image_url, description, category, barcode, status, shelf_life_days, near_expiry_days, block_sale_days_before_expiry, storage_type)
VALUES
    ('SKU-DEMO-001', '可口可乐 330ml', 350, 330, TRUE,
     'https://placehold.co/200x200/1677ff/ffffff/png?text=Cola', '经典可乐', '饮料', '6901028300018', 'ACTIVE', 270, 7, 0, 'AMBIENT'),
    ('SKU-SODA-001', '雪碧 500ml', 400, 500, TRUE,
     'https://placehold.co/200x200/52c41a/ffffff/png?text=Sprite', '柠檬味汽水', '饮料', '6901028300019', 'ACTIVE', 270, 7, 0, 'AMBIENT'),
    ('SKU-WATER-001', '矿泉水 550ml', 200, 550, TRUE,
     'https://placehold.co/200x200/13c2c2/ffffff/png?text=Water', '饮用天然水', '饮料', '6901028300021', 'ACTIVE', 365, 14, 0, 'AMBIENT'),
    ('SKU-SNACK-001', '原味薯片 70g', 650, 70, TRUE,
     'https://placehold.co/200x200/faad14/ffffff/png?text=Chips', '休闲零食', '零食', '6901028300022', 'ACTIVE', 180, 7, 0, 'AMBIENT'),
    ('SKU-MILK-001', '纯牛奶 250ml', 450, 250, TRUE,
     'https://placehold.co/200x200/722ed1/ffffff/png?text=Milk', '常温灭菌乳', '乳品', '6901028300023', 'ACTIVE', 180, 5, 1, 'AMBIENT'),
    ('SKU-NOODLE-001', '红烧牛肉面', 520, 120, TRUE,
     'https://placehold.co/200x200/fa541c/ffffff/png?text=Noodle', '方便食品', '方便食品', '6901028300024', 'ACTIVE', 270, 7, 0, 'AMBIENT')
ON CONFLICT (sku_id) DO UPDATE SET
    sku_name = EXCLUDED.sku_name,
    price_cents = EXCLUDED.price_cents,
    weight_grams = EXCLUDED.weight_grams,
    image_url = EXCLUDED.image_url,
    description = EXCLUDED.description,
    category = EXCLUDED.category,
    barcode = EXCLUDED.barcode,
    status = EXCLUDED.status,
    shelf_life_days = EXCLUDED.shelf_life_days,
    near_expiry_days = EXCLUDED.near_expiry_days,
    block_sale_days_before_expiry = EXCLUDED.block_sale_days_before_expiry,
    storage_type = EXCLUDED.storage_type;

-- 演示柜机库存（SKU 级汇总，与批次可并存）
INSERT INTO device_sku_inventory (device_id, sku_id, quantity, capacity, low_threshold, updated_at)
VALUES
    ('CAB-001', 'SKU-DEMO-001', 3, 20, 5, NOW()),
    ('CAB-001', 'SKU-SODA-001', 4, 20, 5, NOW()),
    ('CAB-001', 'SKU-WATER-001', 8, 24, 6, NOW()),
    ('CAB-001', 'SKU-SNACK-001', 2, 16, 4, NOW()),
    ('CAB-001', 'SKU-MILK-001', 1, 12, 3, NOW()),
    ('CAB-001', 'SKU-NOODLE-001', 5, 16, 4, NOW())
ON CONFLICT (device_id, sku_id) DO UPDATE SET
    quantity = EXCLUDED.quantity,
    capacity = EXCLUDED.capacity,
    low_threshold = EXCLUDED.low_threshold,
    updated_at = NOW();
