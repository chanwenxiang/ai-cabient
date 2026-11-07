-- COMPETITOR_REF: CB-000 (内务清理，无竞品面)
-- UAT R9 增补（2026-11-07）：识别需人工审核实际走 RECOGNITION_FAILED 类型，
-- RECOGNITION_NEEDS_REVIEW 全仓零引用（含前端）=死字典项，清理避免告警配置页出现永不触发的类型。
-- 注意：SLOT_DISCREPANCY 已由 V445 置 INACTIVE（告警面随货道退场），此处不再处理。

DELETE FROM sys_dict_data
 WHERE dict_type = 'merchant_alert_type'
   AND dict_value = 'RECOGNITION_NEEDS_REVIEW';
