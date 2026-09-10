-- V272: 争议结案动作文案（运营/商户按钮与确认框共用）

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
SELECT 'dispute_resolution', '争议结案动作', 'ACTIVE', 0, 'KEEP|WAIVE|CONFIRM|ADJUST'
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'dispute_resolution');

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status, remark)
SELECT v.dict_type, v.dict_value, v.dict_label, v.sort_order, 'ACTIVE', v.remark
FROM (VALUES
    ('dispute_resolution', 'KEEP',    '维持原账单',     1, '维持已生成账单不动'),
    ('dispute_resolution', 'WAIVE',   '免单并退款',     2, '全额退款结案'),
    ('dispute_resolution', 'CONFIRM', '按识别清单结案', 3, '按识别/确认清单落账'),
    ('dispute_resolution', 'ADJUST',  '按调整明细落账', 4, '按运营调整明细补扣或退差')
) AS v(dict_type, dict_value, dict_label, sort_order, remark)
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data d
    WHERE d.dict_type = v.dict_type AND d.dict_value = v.dict_value
);
