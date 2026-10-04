package com.aicabinet.trade.service.view;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.OrderReadModel;

/**
 * 订单详情金额以支付流水为准：cabinet_order.refunded_cents / total 在按行退款后可能被写超或清零。
 */
public final class OrderPaymentLedger {

    private OrderPaymentLedger() {}

    /**
     * @param chargedCents  已完成扣款合计；0 且退款也为 0 时不覆盖读模型
     * @param refundedCents 已完成退款合计
     */
    public static OrderReadModel apply(OrderReadModel model, int chargedCents, int refundedCents) {
        if (model == null) {
            return null;
        }
        int charged = Math.max(0, chargedCents);
        int refunded = Math.max(0, refundedCents);
        if (charged <= 0 && refunded <= 0) {
            return model;
        }
        int remaining = Math.max(0, charged - refunded);
        String status = overlayStatus(model.status(), remaining, refunded);
        int original = model.originalAmountCents() > 0 ? model.originalAmountCents() : charged;
        return new OrderReadModel(
                model.orderId(),
                model.sessionId(),
                model.userId(),
                model.deviceId(),
                model.merchantId(),
                model.deviceName(),
                model.merchantName(),
                remaining,
                original,
                model.couponDiscountCents(),
                model.memberDiscountCents(),
                status,
                model.payChannel(),
                model.lineCount(),
                model.lineSummary(),
                model.payTradeNo(),
                model.paymentOperationId(),
                model.refundedAt(),
                refunded,
                model.inventoryDeducted(),
                model.refundPolicy(),
                model.createdAt(),
                model.paidAt(),
                model.splitStatus(),
                model.lines(),
                model.balanceBeforeCents(),
                model.balanceAfterCents()
        );
    }

    static String overlayStatus(String rawStatus, int remainingCents, int refundedCents) {
        if (refundedCents <= 0) {
            return rawStatus;
        }
        if (remainingCents <= 0) {
            return CabinetConstants.ORDER_STATUS_REFUNDED;
        }
        return "PARTIAL_REFUNDED";
    }
}
