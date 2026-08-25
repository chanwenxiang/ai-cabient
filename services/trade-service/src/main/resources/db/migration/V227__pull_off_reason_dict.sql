-- V227: pull_off_task.reason labels for admin expiry pull-off tab.

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
SELECT 'pull_off_reason', '临期下架原因', 'ACTIVE', 0, 'pull_off_task.reason'
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'pull_off_reason');

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status, remark)
SELECT v.dict_type, v.dict_value, v.dict_label, v.sort_order, 'ACTIVE', v.remark
FROM (VALUES
    ('pull_off_reason', 'EXPIRED',     '已过期', 1, '批次已过效期'),
    ('pull_off_reason', 'NEAR_EXPIRY', '临期',   2, '临近效期需下架')
) AS v(dict_type, dict_value, dict_label, sort_order, remark)
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data d
    WHERE d.dict_type = v.dict_type AND d.dict_value = v.dict_value
);

COMMENT ON COLUMN pull_off_task.reason IS 'EXPIRED|NEAR_EXPIRY；见 pull_off_reason 字典';
