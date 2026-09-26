package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.PaymentOperation;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Mapper
public interface PaymentOperationMapper extends BaseTradeMapper<PaymentOperation> {

    default Optional<PaymentOperation> findByIdempotencyKey(String idempotencyKey) {
    return Optional.ofNullable(selectOne(Wrappers.<PaymentOperation>lambdaQuery().eq(PaymentOperation::getIdempotencyKey, idempotencyKey)));
    }

    default Page<PaymentOperation> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<PaymentOperation>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<PaymentOperation>lambdaQuery().eq(PaymentOperation::getUserId, userId).orderByDesc(PaymentOperation::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    /**
     * 纯冻结/释放类型：只改账户 {@code frozen_cents}，可用余额前后一致（before == after），
     * 对用户不构成「余额变动」，因此不进「余额明细」。
     * <p>与 {@code BalanceLedgerService#holdSignedAmount}、前端 {@code isHoldType} 必须保持一致；
     * 由 {@code scripts/check-balance-hold-types.mjs} 三处互比，禁止只改本清单。</p>
     */
    java.util.List<String> HOLD_OPERATION_TYPES = java.util.List.of(
            "PREAUTH_FREEZE", "PREAUTH_RELEASE", "BALANCE_REFUND_FREEZE", "BALANCE_REFUND_RELEASE");

    /**
     * 余额明细可见流水：在 {@link #findByUserIdOrderByCreatedAtDesc} 基础上剔除纯冻结/释放流水。
     * <p>过滤必须落在 SQL：明细分页取 20 条，若在内存里剔除，整页可能被 19 条冻结/释放占满，
     * 表现为「页内几乎空白 + total 与实际不符 + 加载更多点不动」。逐笔留痕不丢——
     * {@code payment_operation} 仍有全量行（运营/审计侧），会话级冻结另有
     * {@code consumer_preauth_hold} 逐会话一行。</p>
     */
    default Page<PaymentOperation> findVisibleByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable) {
        var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<PaymentOperation>(
                pageable.getPageNumber() + 1L, pageable.getPageSize());
        var result = selectPage(mpPage, Wrappers.<PaymentOperation>lambdaQuery()
                .eq(PaymentOperation::getUserId, userId)
                .notIn(PaymentOperation::getOperationType, HOLD_OPERATION_TYPES)
                .orderByDesc(PaymentOperation::getCreatedAt));
        return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default long countRefundsSince(Long userId, java.time.Instant since) {
        Long n = selectCount(Wrappers.<PaymentOperation>lambdaQuery()
                .eq(PaymentOperation::getUserId, userId)
                .eq(PaymentOperation::getOperationType, "REFUND")
                .ge(PaymentOperation::getCreatedAt, since));
        return n == null ? 0L : n;
    }

    /** 购物单 CHARGE/ADJUST_CHARGE 流水中的网关交易号（legacy 订单 pay_trade_no 为空时用于退款回填）。 */
    default Optional<String> findLatestGatewayTradeNoForCharge(String orderId, String channel) {
        if (orderId == null || orderId.isBlank() || channel == null || channel.isBlank()) {
            return Optional.empty();
        }
        String normalizedChannel = channel.trim().toUpperCase();
        return selectList(Wrappers.<PaymentOperation>lambdaQuery()
                        .eq(PaymentOperation::getOrderId, orderId)
                        .eq(PaymentOperation::getStatus, "COMPLETED")
                        .in(PaymentOperation::getOperationType, "CHARGE", "ADJUST_CHARGE")
                        .isNotNull(PaymentOperation::getGatewayTradeNo)
                        .ne(PaymentOperation::getGatewayTradeNo, "")
                        .orderByDesc(PaymentOperation::getCreatedAt)
                        .last("LIMIT 20"))
                .stream()
                .filter(op -> op.getChannel() != null
                        && normalizedChannel.equals(op.getChannel().trim().toUpperCase()))
                .map(PaymentOperation::getGatewayTradeNo)
                .filter(s -> s != null && !s.isBlank())
                .findFirst();
    }

    /** 购物单最新 CHARGE/ADJUST_CHARGE 操作号（legacy 订单 payment_operation_id 为空时回填）。 */
    default Optional<String> findLatestChargeOperationId(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return Optional.empty();
        }
        return selectList(Wrappers.<PaymentOperation>lambdaQuery()
                        .eq(PaymentOperation::getOrderId, orderId)
                        .eq(PaymentOperation::getStatus, "COMPLETED")
                        .in(PaymentOperation::getOperationType, "CHARGE", "ADJUST_CHARGE")
                        .orderByDesc(PaymentOperation::getCreatedAt)
                        .last("LIMIT 1"))
                .stream()
                .map(PaymentOperation::getOperationId)
                .filter(s -> s != null && !s.isBlank())
                .findFirst();
    }

    /** 充值入账流水 gateway_trade_no（idempotency=recharge-credit:{orderId}）。 */
    default Optional<String> findRechargeCreditGatewayTradeNo(String rechargeOrderId) {
        if (rechargeOrderId == null || rechargeOrderId.isBlank()) {
            return Optional.empty();
        }
        return findByIdempotencyKey("recharge-credit:" + rechargeOrderId.trim())
                .map(PaymentOperation::getGatewayTradeNo)
                .filter(s -> s != null && !s.isBlank());
    }

    default java.util.List<PaymentOperation> findCompletedPaymentOpsByOrderId(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return java.util.List.of();
        }
        return selectList(Wrappers.<PaymentOperation>lambdaQuery()
                .eq(PaymentOperation::getOrderId, orderId)
                .eq(PaymentOperation::getStatus, "COMPLETED")
                .in(PaymentOperation::getOperationType, "CHARGE", "ADJUST_CHARGE", "REFUND"));
    }

    /** 已完成 CHARGE/ADJUST_CHARGE 减 REFUND 的净入账（分）。 */
    default int netCompletedCents(String orderId) {
        int net = 0;
        for (PaymentOperation op : findCompletedPaymentOpsByOrderId(orderId)) {
            net += switch (op.getOperationType()) {
                case "CHARGE", "ADJUST_CHARGE" -> op.getAmountCents();
                case "REFUND" -> -op.getAmountCents();
                default -> 0;
            };
        }
        return net;
    }

    /**
     * 对账口径：已完成流水的净现金流入（购物 CHARGE/ADJUST + 充值 RECHARGE − 各类 REFUND）。
     */
    @Select("""
            SELECT COALESCE(SUM(CASE
              WHEN operation_type IN ('CHARGE', 'ADJUST_CHARGE', 'RECHARGE') THEN amount_cents
              WHEN operation_type IN ('REFUND', 'RECHARGE_REFUND') THEN -amount_cents
              ELSE 0 END), 0)
            FROM payment_operation
            WHERE status = 'COMPLETED'
              AND created_at >= #{start} AND created_at < #{end}
              AND UPPER(channel) = UPPER(#{channel})
            """)
    long sumNetCashflowBetween(@Param("start") java.time.Instant start,
                               @Param("end") java.time.Instant end,
                               @Param("channel") String channel);

    /**
     * 窗口内网关渠道（WECHAT/ALIPAY/MOCK）已完成充值退款合计（正数）。
     * 与 Mock/通道账单负向退款对齐。
     */
    @Select("""
            SELECT COALESCE(SUM(amount_cents), 0)
            FROM payment_operation
            WHERE status = 'COMPLETED'
              AND operation_type = 'RECHARGE_REFUND'
              AND created_at >= #{start} AND created_at < #{end}
              AND UPPER(channel) IN ('WECHAT', 'ALIPAY', 'MOCK')
            """)
    long sumGatewayRechargeRefundBetween(@Param("start") java.time.Instant start,
                                         @Param("end") java.time.Instant end);

    /** 指定渠道窗口内已完成充值退款合计（正数）。 */
    @Select("""
            SELECT COALESCE(SUM(amount_cents), 0)
            FROM payment_operation
            WHERE status = 'COMPLETED'
              AND operation_type = 'RECHARGE_REFUND'
              AND created_at >= #{start} AND created_at < #{end}
              AND UPPER(channel) = UPPER(#{channel})
            """)
    long sumRechargeRefundByChannel(@Param("start") java.time.Instant start,
                                    @Param("end") java.time.Instant end,
                                    @Param("channel") String channel);

    /** 对账匹配：窗口内有购物入账流水的订单号（不含充值）。 */
    @Select("""
            SELECT DISTINCT order_id FROM payment_operation
            WHERE status = 'COMPLETED'
              AND created_at >= #{start} AND created_at < #{end}
              AND UPPER(channel) = UPPER(#{channel})
              AND order_id IS NOT NULL
              AND operation_type IN ('CHARGE', 'ADJUST_CHARGE', 'REFUND')
            """)
    java.util.List<String> findDistinctCabinetOrderIdsBetween(@Param("start") java.time.Instant start,
                                                             @Param("end") java.time.Instant end,
                                                             @Param("channel") String channel);

}
