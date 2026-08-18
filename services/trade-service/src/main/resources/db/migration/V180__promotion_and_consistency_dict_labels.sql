-- OBS-006 / OBS-028：补齐营销类型与一致性检查类型字典项
INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status, remark)
SELECT 'promotion_type', 'NEW_USER', '新客', 50, 'ACTIVE', 'OBS-006'
WHERE EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'promotion_type')
  AND NOT EXISTS (
    SELECT 1 FROM sys_dict_data WHERE dict_type = 'promotion_type' AND dict_value = 'NEW_USER'
  );

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status, remark)
SELECT 'promotion_type', 'POINTS', '积分', 60, 'ACTIVE', 'OBS-006'
WHERE EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'promotion_type')
  AND NOT EXISTS (
    SELECT 1 FROM sys_dict_data WHERE dict_type = 'promotion_type' AND dict_value = 'POINTS'
  );

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status, remark)
SELECT 'consistency_check_type', 'POINTS_BALANCE', '积分余额', 40, 'ACTIVE', 'OBS-028'
WHERE EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'consistency_check_type')
  AND NOT EXISTS (
    SELECT 1 FROM sys_dict_data
    WHERE dict_type = 'consistency_check_type' AND dict_value = 'POINTS_BALANCE'
  );

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status, remark)
SELECT 'consistency_check_type', 'COUPON_ISSUED', '发券数量', 50, 'ACTIVE', 'OBS-028'
WHERE EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'consistency_check_type')
  AND NOT EXISTS (
    SELECT 1 FROM sys_dict_data
    WHERE dict_type = 'consistency_check_type' AND dict_value = 'COUPON_ISSUED'
  );
