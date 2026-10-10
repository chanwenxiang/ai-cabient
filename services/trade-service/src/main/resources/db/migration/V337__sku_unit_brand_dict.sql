-- MIGRATION_KIND: backfill
-- CB-026：商品建档枚举字段字典化——新增「商品单位 / 商品品牌」两个运维可维护字典类型。
-- 与 packages/shared-dict DICT 基线保持一致：sku_unit 预置常见单位兜底；sku_brand 为开放集，仅建类型、由运营在「字典管理」维护。
-- runtime 下发经 DictRuntimeController；前端下拉 useDictOptions('sku_unit'/'sku_brand')。

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
VALUES ('sku_unit', '商品单位', 'ACTIVE', 120, '商品建档单位下拉；运营可在字典管理增删')
ON CONFLICT (dict_type) DO NOTHING;

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
VALUES ('sku_brand', '商品品牌', 'ACTIVE', 121, '商品建档品牌下拉；开放集，由运营在字典管理维护')
ON CONFLICT (dict_type) DO NOTHING;

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status)
SELECT v.dict_type, v.dict_value, v.dict_label, v.sort_order, 'ACTIVE'
FROM (VALUES
    ('sku_unit'::varchar, '件'::varchar, '件'::varchar, 1),
    ('sku_unit',          '瓶',          '瓶',          2),
    ('sku_unit',          '罐',          '罐',          3),
    ('sku_unit',          '盒',          '盒',          4),
    ('sku_unit',          '袋',          '袋',          5),
    ('sku_unit',          '桶',          '桶',          6),
    ('sku_unit',          '个',          '个',          7)
) AS v(dict_type, dict_value, dict_label, sort_order)
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data d
    WHERE d.dict_type = v.dict_type AND d.dict_value = v.dict_value
);
