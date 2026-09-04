-- 商户告警：柜机停售（营业锁机）可订阅类型
INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status)
SELECT 'merchant_alert_type', 'SALES_LOCKED', '柜机停售', 9, 'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data
    WHERE dict_type = 'merchant_alert_type' AND dict_value = 'SALES_LOCKED'
);

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status)
SELECT 'ops_alert_type', 'SALES_LOCKED', '柜机停售', 9, 'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data
    WHERE dict_type = 'ops_alert_type' AND dict_value = 'SALES_LOCKED'
);
