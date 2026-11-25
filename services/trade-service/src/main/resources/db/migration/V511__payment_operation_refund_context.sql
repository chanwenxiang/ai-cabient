-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes（payment_operation 增加可空 JSONB，持久化退款调用方续跑上下文）
-- COMPETITOR_REF: CB-061（退款逐笔台账与渠道幂等；异步退款完成后补跑本地下游）
-- TABLES: payment_operation
-- LOCK_RISK: low（仅增加可空列；PG 11+ 不重写表）
-- ROLLBACK: ALTER TABLE payment_operation DROP COLUMN IF EXISTS refund_context_json
-- NOTES: 订单退款上下文记录全额/按行、回库策略、退款行、总额、原因与下游收尾状态；
--        充值退款上下文记录切片/全额语义，供查单成功后幂等推进本地。

ALTER TABLE payment_operation
    ADD COLUMN IF NOT EXISTS refund_context_json JSONB NULL;

COMMENT ON COLUMN payment_operation.refund_context_json IS
    '退款续跑上下文（JSONB）：订单 FULL/PARTIAL 或充值 RECHARGE；渠道 PROCESSING/COMPLETED 后可安全补跑本地下游';

CREATE INDEX IF NOT EXISTS idx_payment_operation_refund_resume
    ON payment_operation (created_at)
    WHERE channel = 'WECHAT'
      AND status IN ('REFUND_PROCESSING', 'COMPLETED')
      AND refund_context_json IS NOT NULL;
