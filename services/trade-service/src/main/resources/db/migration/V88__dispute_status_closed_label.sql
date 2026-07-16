-- Align CLOSED dispute status label with shared-dict (was duplicate 已结案).
UPDATE sys_dict_data
SET dict_label = '已关闭'
WHERE dict_type = 'dispute_status'
  AND dict_value = 'CLOSED'
  AND dict_label <> '已关闭';
