-- COMPETITOR_REF: CB-000 (内务清理，无竞品面)
-- UAT R9 拍板：两张 2026-10-11 视觉列备份表确认无回滚需求，生产一并 DROP。
-- 来源：docs/uat/table-coverage-final.md C 类死表候选，用户 2026-11-07 拍板删除。

DROP TABLE IF EXISTS sku_catalog_vision_cols_bak_20261011;
DROP TABLE IF EXISTS sku_vision_mapping_bak_20261011;
