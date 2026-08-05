-- V145 partially missed rows depending on encoding; re-apply by sku_code only.
UPDATE sku_catalog SET image_url = '/admin/sku-demo/cola.svg'   WHERE sku_code = 100001;
UPDATE sku_catalog SET image_url = '/admin/sku-demo/apple.svg'  WHERE sku_code = 100002;
UPDATE sku_catalog SET image_url = '/admin/sku-demo/sprite.svg' WHERE sku_code = 100003;
UPDATE sku_catalog SET image_url = '/admin/sku-demo/milk.svg'   WHERE sku_code = 100004;
UPDATE sku_catalog SET image_url = '/admin/sku-demo/noodle.svg' WHERE sku_code = 100005;
UPDATE sku_catalog SET image_url = '/admin/sku-demo/chips.svg'  WHERE sku_code = 100006;
UPDATE sku_catalog SET image_url = '/admin/sku-demo/water.svg'  WHERE sku_code = 100007;

UPDATE sku_catalog
SET image_url = '/admin/sku-demo/default.svg'
WHERE sku_code IS NOT NULL
  AND (image_url IS NULL OR btrim(image_url) = '' OR image_url LIKE 'https://placehold.co/%' OR image_url LIKE 'https://cdn.jsdelivr.net/%');
