package com.aicabinet.trade.service.view;

import com.aicabinet.common.dto.OrderLineDto;
import com.aicabinet.common.dto.OrderReadModel;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CabinetOrderLine;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 订单读模型唯一装配点：渠道归一、原价回退、行摘要等派生逻辑只在此处计算。
 */
@Component
public class OrderViewAssembler {

    /**
     * @param lines           订单行；可空
     * @param splitStatus     分账状态；可空
     * @param paidAt          支付完成时间；可空（调用方解析后传入）
     * @param refundPolicy    柜机退款策略名；可空
     * @param payTradeNo      覆盖支付流水号；null 则用订单字段
     * @param includeLines    是否输出明细行（详情 true，列表 false）
     */
    public OrderReadModel assemble(CabinetOrder order,
                                   List<CabinetOrderLine> lines,
                                   String splitStatus,
                                   Instant paidAt,
                                   String refundPolicy,
                                   String payTradeNo,
                                   boolean includeLines) {
        if (order == null) {
            throw new IllegalArgumentException("order required");
        }
        List<CabinetOrderLine> safeLines = lines == null ? List.of() : lines;
        int coupon = Math.max(0, order.getCouponDiscountCents());
        int member = Math.max(0, order.getMemberDiscountCents());
        int original = originalAmountCents(order, coupon, member);
        String channel = normalizePayChannel(order);
        String tradeNo = (payTradeNo != null && !payTradeNo.isBlank())
                ? payTradeNo
                : blankToNull(order.getPayTradeNo());
        List<OrderLineDto> lineDtos = includeLines ? toLineDtos(safeLines) : null;
        return new OrderReadModel(
                order.getOrderId(),
                order.getSessionId(),
                order.getUserId(),
                order.getDeviceId(),
                blankToNull(order.getMerchantId()),
                blankToNull(order.getDeviceName()),
                blankToNull(order.getMerchantName()),
                order.getTotalAmountCents(),
                original,
                coupon,
                member,
                order.getStatus(),
                channel,
                itemQty(safeLines),
                buildLineSummary(safeLines),
                tradeNo,
                blankToNull(order.getPaymentOperationId()),
                order.getRefundedAt(),
                Math.max(0, order.getRefundedCents()),
                order.isInventoryDeducted(),
                blankToNull(refundPolicy),
                order.getCreatedAt(),
                paidAt,
                blankToNull(splitStatus),
                lineDtos,
                includeLines ? order.getBalanceBeforeCents() : null,
                includeLines ? order.getBalanceAfterCents() : null
        );
    }

    public OrderReadModel assembleSummary(CabinetOrder order,
                                          List<CabinetOrderLine> lines,
                                          String splitStatus,
                                          Instant paidAt,
                                          String refundPolicy,
                                          String payTradeNo) {
        return assemble(order, lines, splitStatus, paidAt, refundPolicy, payTradeNo, false);
    }

    public OrderReadModel assembleDetail(CabinetOrder order,
                                         List<CabinetOrderLine> lines,
                                         String splitStatus,
                                         Instant paidAt,
                                         String refundPolicy,
                                         String payTradeNo) {
        return assemble(order, lines, splitStatus, paidAt, refundPolicy, payTradeNo, true);
    }

    /** 余额账本扣款以 BL- 操作号为准，与三端历史口径一致。 */
    public static String normalizePayChannel(CabinetOrder order) {
        String channel = order.getPayChannel();
        if (order.getPaymentOperationId() != null && order.getPaymentOperationId().startsWith("BL-")) {
            channel = "BALANCE";
        }
        if (channel == null || channel.isBlank()) {
            return "UNKNOWN";
        }
        return channel;
    }

    public static int originalAmountCents(CabinetOrder order, int coupon, int member) {
        if (order.getOriginalAmountCents() > 0) {
            return order.getOriginalAmountCents();
        }
        return order.getTotalAmountCents() + coupon + member;
    }

    public static int itemQty(List<CabinetOrderLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return 0;
        }
        return lines.stream().mapToInt(l -> Math.max(0, l.getQuantity())).sum();
    }

    /**
     * 商品摘要：名称 x数量 ·货道 ·批次；超过 2 行追加「等N件」。
     * 三端统一口径（原 admin「等N种」已收敛到此）。
     */
    public static String buildLineSummary(List<CabinetOrderLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        String preview = lines.stream()
                .limit(2)
                .map(l -> {
                    String sku = l.getSkuName() == null || l.getSkuName().isBlank()
                            ? l.getSkuId()
                            : l.getSkuName();
                    String name = sku + " x" + l.getQuantity();
                    if (l.getSlotId() != null && !l.getSlotId().isBlank()) {
                        name += " ·货道" + l.getSlotId().trim();
                    }
                    if (l.getBatchNo() != null && !l.getBatchNo().isBlank()) {
                        name += " @" + l.getBatchNo().trim();
                    }
                    return name;
                })
                .reduce((a, b) -> a + "、" + b)
                .orElse("");
        if (lines.size() > 2) {
            return preview + " 等" + lines.size() + "件";
        }
        return preview;
    }

    private static List<OrderLineDto> toLineDtos(List<CabinetOrderLine> lines) {
        return lines.stream()
                .map(l -> new OrderLineDto(
                        l.getSkuId(), l.getSkuName(), l.getQuantity(),
                        l.getUnitPriceCents(), l.getLineAmountCents(), l.getBatchNo(), l.getSlotId()))
                .toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
