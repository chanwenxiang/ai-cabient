package com.aicabinet.trade.reconciliation;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.config.ReconciliationProperties;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.domain.RechargeOrder;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.aicabinet.trade.mapper.RechargeOrderMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 本地 dev：用账本数据模拟<strong>支付通道</strong>账单（与 ledger 中微信/支付宝入账一致）。
 * 余额扣款订单不会出现在真实微信/支付宝对账单中，故 mock 也不纳入，避免联调误报差异。
 */
@Component
public class MockPlatformBillProvider implements PlatformBillProvider {

    private static final Set<String> GATEWAY_PAY_CHANNELS = Set.of(
            CabinetConstants.PAY_CHANNEL_WECHAT,
            "ALIPAY",
            "MOCK"
    );
    private static final Set<String> BILLABLE_ORDER_STATUSES = Set.of("PAID", "REFUNDED");
    private static final Set<String> GATEWAY_REFUND_TYPES = Set.of("REFUND", "RECHARGE_REFUND");

    private final ReconciliationProperties properties;
    private final CabinetOrderMapper orderRepository;
    private final RechargeOrderMapper rechargeRepository;
    private final PaymentOperationMapper paymentOperationRepository;

    public MockPlatformBillProvider(ReconciliationProperties properties,
                                    CabinetOrderMapper orderRepository,
                                    RechargeOrderMapper rechargeRepository,
                                    PaymentOperationMapper paymentOperationRepository) {
        this.properties = properties;
        this.orderRepository = orderRepository;
        this.rechargeRepository = rechargeRepository;
        this.paymentOperationRepository = paymentOperationRepository;
    }

    @Override
    public String channel() {
        return "MOCK";
    }

    @Override
    public List<PlatformBillLine> fetchDailyBill(LocalDate date) {
        if (!properties.mockEnabled()) {
            return List.of();
        }
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Instant start = date.atStartOfDay(zone).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(zone).toInstant();
        List<PlatformBillLine> lines = new ArrayList<>();

        for (CabinetOrder order : orderRepository.findByCreatedAtBetween(start, end)) {
            if (!isGatewayBillableOrder(order)) {
                continue;
            }
            lines.add(new PlatformBillLine(
                    "WX-MOCK-" + order.getOrderId(),
                    order.getOrderId(),
                    order.getTotalAmountCents(),
                    order.getCreatedAt(),
                    "PAY",
                    "{\"source\":\"mock\",\"type\":\"order\",\"payChannel\":\""
                            + safeChannel(order.getPayChannel()) + "\"}"
            ));
        }
        for (RechargeOrder recharge : rechargeRepository.findPaidBetween(start, end)) {
            lines.add(new PlatformBillLine(
                    "WX-MOCK-RCH-" + recharge.getOrderId(),
                    recharge.getOrderId(),
                    recharge.getAmountCents(),
                    recharge.getPaidAt(),
                    "RECHARGE",
                    "{\"source\":\"mock\",\"type\":\"recharge\",\"status\":\""
                            + safeChannel(recharge.getStatus()) + "\"}"
            ));
        }
        // 通道退款（负向）与 ledger 中 WECHAT/ALIPAY RECHARGE_REFUND/REFUND 对齐
        for (PaymentOperation refund : findGatewayRefunds(start, end)) {
            String merchantOrderNo = firstNonBlank(refund.getOrderId(),
                    rechargeOrderIdFromIdempotency(refund.getIdempotencyKey()));
            lines.add(new PlatformBillLine(
                    "WX-MOCK-RF-" + refund.getOperationId(),
                    merchantOrderNo,
                    -Math.abs(refund.getAmountCents()),
                    refund.getCreatedAt(),
                    "REFUND",
                    "{\"source\":\"mock\",\"type\":\"refund\",\"operationType\":\""
                            + safeChannel(refund.getOperationType()) + "\"}"
            ));
        }
        return lines;
    }

    private List<PaymentOperation> findGatewayRefunds(Instant start, Instant end) {
        return paymentOperationRepository.selectList(Wrappers.<PaymentOperation>lambdaQuery()
                .eq(PaymentOperation::getStatus, "COMPLETED")
                .in(PaymentOperation::getOperationType, GATEWAY_REFUND_TYPES)
                .in(PaymentOperation::getChannel, GATEWAY_PAY_CHANNELS)
                .ge(PaymentOperation::getCreatedAt, start)
                .lt(PaymentOperation::getCreatedAt, end));
    }

    /** 仅模拟支付通道入账：排除余额等站内扣款。 */
    static boolean isGatewayBillableOrder(CabinetOrder order) {
        if (order == null) {
            return false;
        }
        String status = order.getStatus() == null ? "" : order.getStatus().trim().toUpperCase(Locale.ROOT);
        if (!BILLABLE_ORDER_STATUSES.contains(status)) {
            return false;
        }
        String channel = order.getPayChannel() == null ? "" : order.getPayChannel().trim().toUpperCase(Locale.ROOT);
        return GATEWAY_PAY_CHANNELS.contains(channel);
    }

    static String rechargeOrderIdFromIdempotency(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        String key = idempotencyKey.trim();
        // recharge-refund:{orderId} 或 RECHARGE-REFUND:{orderId}:amount:...
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.startsWith("recharge-refund:")) {
            String rest = key.substring("recharge-refund:".length());
            int colon = rest.indexOf(':');
            return colon > 0 ? rest.substring(0, colon) : rest;
        }
        return null;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return null;
    }

    private static String safeChannel(String payChannel) {
        return payChannel == null ? "" : payChannel.trim();
    }
}
