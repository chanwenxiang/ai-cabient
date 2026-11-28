-- #47 人工处置留操作人；#48 阈值不 seed，保留环境变量/代码默认回退。
-- COMPETITOR_REF: CB-012（异常订单必须留人工复核/决策位）
ALTER TABLE risk_event
    ADD COLUMN IF NOT EXISTS disposition_operator_id BIGINT;

COMMENT ON COLUMN risk_event.disposition_operator_id IS '人工处置操作人 ID；自动处置为空';
