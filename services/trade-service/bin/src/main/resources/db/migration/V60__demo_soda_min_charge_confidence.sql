-- 演示 SKU 雪碧：soda.jpg YOLO 置信度约 82%，默认 92% 会进 DISPUTED；沙箱/演示环境降至 80% 以支持自动扣款。
-- 修改方式见 docs/VISION_YOLO_TEST.md §8.6

UPDATE sku_catalog
SET min_charge_confidence = 0.80
WHERE sku_id = 'SKU-SODA-001';
