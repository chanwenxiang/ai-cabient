-- COMPETITOR_REF: CB-000 (内务清理，无竞品面)
-- 要货功能 2026-10-13 下线（ApprovalWorkflowService 注释为证：MERCHANT_REPLEN_REQUEST 审批不再产生），
-- 清理其审批定义种子与字典项，避免运营台出现永不生效的「商户要货审批」配置。
-- UAT 实证（2026-11-07）：approval_instance 0 行、无任何代码路径拉起；API DELETE + 字典 DELETE 已在 uat 验证。

DELETE FROM approval_definition WHERE biz_type = 'MERCHANT_REPLEN_REQUEST';
DELETE FROM sys_dict_data WHERE dict_type = 'approval_biz_type' AND dict_value = 'MERCHANT_REPLEN_REQUEST';
