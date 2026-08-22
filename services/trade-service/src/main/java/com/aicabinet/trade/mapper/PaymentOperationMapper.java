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
