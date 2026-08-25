-- 清理仍指向旧 SVG 占位图的演示商品，统一回落到真实商品照片
UPDATE sku_catalog
SET image_url = '/admin/sku-demo/default.jpg'
WHERE image_url LIKE '/admin/sku-demo/%.svg';
