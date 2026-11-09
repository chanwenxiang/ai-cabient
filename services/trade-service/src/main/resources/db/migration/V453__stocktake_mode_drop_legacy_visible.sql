-- 盘点方式遗留码 VISIBLE 与 OPEN 同译「明盘」，违反同类型标签唯一（DictLabelUniquenessIT）。
-- 域模型只用 OPEN/BLIND，VISIBLE 无引用，清理。
DELETE FROM sys_dict_data WHERE dict_type = 'stocktake_mode' AND dict_value = 'VISIBLE';
