-- MIGRATION_KIND: backfill
-- COMPETITOR_REF: CB-082
-- 活动展示位 v1 配套（用户 2026-11-01 拍板）：素材记录原始像素尺寸，配置展示位时按位置校验；
-- 展示位位置进字典（后备/管理页标签走 dict，不写裸码）。

ALTER TABLE media_asset
    ADD COLUMN IF NOT EXISTS image_width INT,
    ADD COLUMN IF NOT EXISTS image_height INT;

INSERT INTO sys_dict_type (dict_type, dict_name, status, remark, sort_order)
SELECT 'promotion_placement_position', '活动展示位', 'ACTIVE', '活动轮播横幅/弹窗位中文映射', 120
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'promotion_placement_position');

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status)
SELECT v.dict_type, v.dict_value, v.dict_label, v.sort_order, 'ACTIVE'
FROM (VALUES
    ('promotion_placement_position'::varchar, 'BANNER'::varchar, '轮播横幅'::varchar, 1),
    ('promotion_placement_position'::varchar, 'POPUP_BEFORE_OPEN'::varchar, '开门前弹窗'::varchar, 2),
    ('promotion_placement_position'::varchar, 'POPUP_AFTER_CLOSE'::varchar, '关门后弹窗'::varchar, 3)
) AS v(dict_type, dict_value, dict_label, sort_order)
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data d
    WHERE d.dict_type = v.dict_type AND d.dict_value = v.dict_value
);
