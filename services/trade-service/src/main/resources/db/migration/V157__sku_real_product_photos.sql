-- 演示商品主图切换为真实商品照片（管理端 public/sku-demo/*.jpg，三端共用同一 imageUrl）

UPDATE sku_catalog SET image_url = '/admin/sku-demo/cola.jpg'
WHERE sku_code = 100001 OR sku_id IN ('SKU-DEMO-001', 'SKU-COLA', 'SKU-100001') OR sku_name LIKE '可口可乐%';

UPDATE sku_catalog SET image_url = '/admin/sku-demo/default.jpg'
WHERE sku_code = 100002 OR sku_id = 'SKU-APPLE-001' OR sku_name = '苹果';

UPDATE sku_catalog SET image_url = '/admin/sku-demo/sprite.jpg'
WHERE sku_code = 100003 OR sku_id = 'SKU-SODA-001' OR sku_name LIKE '雪碧%';

UPDATE sku_catalog SET image_url = '/admin/sku-demo/milk.jpg'
WHERE sku_code = 100004 OR sku_id = 'SKU-MILK-001' OR sku_name LIKE '纯牛奶%';

UPDATE sku_catalog SET image_url = '/admin/sku-demo/noodle.jpg'
WHERE sku_code = 100005 OR sku_id = 'SKU-NOODLE-001' OR sku_name LIKE '红烧牛肉面%';

UPDATE sku_catalog SET image_url = '/admin/sku-demo/chips.jpg'
WHERE sku_code = 100006 OR sku_id = 'SKU-SNACK-001' OR sku_name LIKE '原味薯片%';

UPDATE sku_catalog SET image_url = '/admin/sku-demo/water.jpg'
WHERE sku_code = 100007 OR sku_id = 'SKU-WATER-001' OR sku_name LIKE '矿泉水%';

-- 其余演示商品统一兜底为默认商品照片
UPDATE sku_catalog
SET image_url = '/admin/sku-demo/default.jpg'
WHERE sku_code BETWEEN 100001 AND 100007
  AND (image_url IS NULL OR btrim(image_url) = ''
       OR image_url LIKE 'https://placehold.co/%'
       OR image_url LIKE 'https://cdn.jsdelivr.net/%'
       OR image_url LIKE '/admin/sku-demo/%.svg');
