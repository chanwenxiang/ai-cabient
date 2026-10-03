-- MIGRATION_KIND: backfill
-- 商家待办分类「未知」修复：工作台 actionItems 会下发识别/上传类告警
-- （RECOGNITION_FAILED/RECOGNITION_TIMEOUT/RECOGNITION_NEEDS_REVIEW/UPLOAD_STUCK），
-- 字典缺这些 key 时 displayLabel 对纯大写码回退「未知」。补齐并覆盖 SALES_LOCKED/DOOR_OPEN_TOO_LONG。
INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status)
SELECT v.dict_type, v.dict_value, v.dict_label, v.sort_order, 'ACTIVE'
FROM (VALUES
    ('merchant_alert_type', 'RECOGNITION_FAILED', '识别结果需人工审核', 9),
    ('merchant_alert_type', 'RECOGNITION_TIMEOUT', '识别超时', 10),
    ('merchant_alert_type', 'RECOGNITION_NEEDS_REVIEW', '识别需人工审核', 11),
    ('merchant_alert_type', 'UPLOAD_STUCK', '视频上传滞留', 12),
    ('merchant_alert_type', 'SALES_LOCKED', '柜机停售', 13),
    ('merchant_alert_type', 'DOOR_OPEN_TOO_LONG', '开门超时', 14)
) AS v(dict_type, dict_value, dict_label, sort_order)
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data d
    WHERE d.dict_type = v.dict_type AND d.dict_value = v.dict_value
);
