-- Align session_kind dict with API values (CONSUMER / RESTOCK / OPS).
INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status, remark)
SELECT 'session_kind', 'CONSUMER', '消费', 0, 'ACTIVE', '消费购物会话'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data
    WHERE dict_type = 'session_kind' AND dict_value = 'CONSUMER'
);
