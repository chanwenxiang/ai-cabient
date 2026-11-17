-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- COMPETITOR_REF: CB-061（out_refund_no/渠道单号幂等键官方要求：唯一性只约束已结算资金流水，审计痕迹另计）
-- TABLES: payment_operation（核心资金表；行数按交易规模，开发库小表）
-- LOCK_RISK: high（DROP+CREATE 期间 ACCESS EXCLUSIVE 短锁；Flyway 社区版事务内不能跑 CONCURRENTLY，对齐 V305/V301 先例：开发库秒级，生产低峰执行）
-- ROLLBACK: 恢复 V239 原索引前必须先清理「审计行与资金行同号」的行，否则 CREATE UNIQUE INDEX 失败
-- NOTES: V491/V490 的「事务外痕迹行」与 V239 全表唯一索引天然冲突——
--   扣款：CHARGE_PENDING（成功后 FINALIZED）痕迹行要与 COMPLETED 资金行共用同一 gateway_trade_no
--   （外层事务回滚时痕迹行是渠道单号的唯一耐久证据）；
--   退款：REFUND_INITIATED 痕迹行与 COMPLETED 退款行共用 deterministicRefundNo
--   （RefundRetryScheduler 必须用原 out_refund_no 查单/同号重发）。
--   真渠道链路（非 mock）落资金行时必撞 uk_payment_operation_gateway_trade_no ⇒ 409 且钱已扣。
--   唯一性只属于「已结算资金行」：同一渠道结算单号仍只能落一条 COMPLETED 流水；
--   审计状态（CHARGE_PENDING / INITIATED / FINALIZED / REFUND_INITIATED / REFUND_FAILED）不占该唯一位。

DROP INDEX IF EXISTS uk_payment_operation_gateway_trade_no;

CREATE UNIQUE INDEX uk_payment_operation_gateway_trade_no
    ON payment_operation (gateway_trade_no)
    WHERE gateway_trade_no IS NOT NULL
      AND gateway_trade_no <> ''
      AND status = 'COMPLETED';

COMMENT ON INDEX uk_payment_operation_gateway_trade_no IS
    '同一渠道结算单号只能落一条 COMPLETED 资金流水；H41/G1/M27 的审计痕迹行允许与资金行同号（V494）';
