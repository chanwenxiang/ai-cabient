-- Point demo SKU cover images to same-origin admin static assets
-- so thumbnails render without depending on external CDNs.

UPDATE sku_catalog SET image_url = '/admin/sku-demo/cola.svg'
WHERE sku_code = 100001 OR sku_id IN ('SKU-COLA', 'SKU-100001') OR sku_name LIKE '可口可乐%';

UPDATE sku_catalog SET image_url = '/admin/sku-demo/apple.svg'
WHERE sku_code = 100002 OR sku_name = '苹果';

UPDATE sku_catalog SET image_url = '/admin/sku-demo/sprite.svg'
WHERE sku_code = 100003 OR sku_name LIKE '雪碧%';

UPDATE sku_catalog SET image_url = '/admin/sku-demo/milk.svg'
WHERE sku_code = 100004 OR sku_name LIKE '纯牛奶%';

UPDATE sku_catalog SET image_url = '/admin/sku-demo/noodle.svg'
WHERE sku_code = 100005 OR sku_name LIKE '红烧牛肉面%';

UPDATE sku_catalog SET image_url = '/admin/sku-demo/chips.svg'
WHERE sku_code = 100006 OR sku_name LIKE '原味薯片%';

UPDATE sku_catalog SET image_url = '/admin/sku-demo/water.svg'
WHERE sku_code = 100007 OR sku_name LIKE '矿泉水%';

-- Any remaining seed rows that still use external placeholders
UPDATE sku_catalog
SET image_url = '/admin/sku-demo/default.svg'
WHERE (image_url IS NULL OR image_url = '' OR image_url LIKE 'https://placehold.co/%' OR image_url LIKE 'https://cdn.jsdelivr.net/%')
  AND sku_code BETWEEN 100001 AND 100007;
