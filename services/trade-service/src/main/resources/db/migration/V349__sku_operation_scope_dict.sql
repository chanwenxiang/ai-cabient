-- MIGRATION_KIND: backfill（sku_catalog 加 1 列 + 经营归属字典种子数据；任务#11）
-- COMPETITOR_REF: CB-036
-- LOCK_RISK: low（ALTER TABLE ADD COLUMN 可空不重写表；字典幂等 INSERT）
-- TABLES: sku_catalog (+1) | sys_dict_type (+0) | sys_dict_data (+0)
-- NOTES: 商品经营归属字段（任务#11，2026-10-13 19:19 拍板 + 2026-10-14 用户确认走字典）。
--        ① sku_catalog.operation_scope varchar(32) 可空：NULL=未分类（存量不默认归类，
--          宁可空着不可错标——错标会固化进月结/对账历史口径，见 CB-036 结论 3）。
--        ② 字典 sys_dict_type=sku_operation_scope：DIRECT=直营 / NON_DIRECT=非直营，
--          可扩展（后续「联营/代销」走字典管理页零迁移）。
--        ③ 三层归属的第三层「外部渠道商品」不占字典值：由 V347 external_product
--          独立库承载（CB-036 结论 2，主数据不被污染）。

-- ── 1. sku_catalog：经营归属列 ──────────────────────────────────────────────────────────
ALTER TABLE sku_catalog
    ADD COLUMN operation_scope varchar(32);

-- ── 2. 经营归属字典（前端建档下拉 label + 列表筛选；取值校验在应用层 OpsCatalogAdminService）──
INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
SELECT 'sku_operation_scope', '商品经营归属', 'ACTIVE', 910,
       'SKU 主数据经营模式分类（直营/非直营，可扩展）；外部渠道商品独立 external_product 库不占此字典'
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'sku_operation_scope');

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status, remark)
SELECT v.dict_type, v.dict_value, v.dict_label, v.sort_order, 'ACTIVE', v.remark
FROM (VALUES
    ('sku_operation_scope', 'DIRECT',     '直营',   1, '我方直营商品（默认建档值）'),
    ('sku_operation_scope', 'NON_DIRECT', '非直营', 2, '非直营/联营/代销等混入商品')
) AS v(dict_type, dict_value, dict_label, sort_order, remark)
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data d
    WHERE d.dict_type = v.dict_type AND d.dict_value = v.dict_value
);
