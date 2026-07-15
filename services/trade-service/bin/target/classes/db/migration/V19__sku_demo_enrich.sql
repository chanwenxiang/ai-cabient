-- 演示商品补全图片、分类、重量、条码（运营后台商品管理展示）

UPDATE sku_catalog SET
    image_url = 'https://cdn.jsdelivr.net/npm/@tabler/icons@2.47.0/icons/bottle.svg',
    category = '饮料',
    weight_grams = 330,
    barcode = '6901028300018',
    price_cents = 350
WHERE sku_id = 'SKU-DEMO-001';

UPDATE sku_catalog SET
    image_url = 'https://cdn.jsdelivr.net/npm/@tabler/icons@2.47.0/icons/bottle.svg',
    category = '饮料',
    weight_grams = 500,
    barcode = '6901028300019',
    price_cents = 400
WHERE sku_id = 'SKU-SODA-001';

UPDATE sku_catalog SET
    image_url = 'https://cdn.jsdelivr.net/npm/@tabler/icons@2.47.0/icons/apple.svg',
    category = '生鲜',
    weight_grams = 200,
    barcode = '6901028300020',
    price_cents = 300
WHERE sku_id = 'SKU-APPLE-001';
