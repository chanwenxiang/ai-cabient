-- OBS-002：公告状态 ARCHIVED 文案与操作「归档」对齐
UPDATE sys_dict_data
SET dict_label = '已归档',
    updated_at = NOW()
WHERE dict_type = 'announcement_status'
  AND dict_value = 'ARCHIVED'
  AND dict_label IS DISTINCT FROM '已归档';
