package com.aicabinet.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonView;

import java.time.Instant;
import java.util.List;

/**
 * 三端共用的订单聚合读模型（字段并集）。
 * 列表接口 {@code lines} 为 null；详情接口填充明细。
 * 序列化时配合 {@link OrderViews} 按端裁剪。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderReadModel(
        @JsonView(OrderViews.Public.class) String orderId,
        @JsonView(OrderViews.Public.class) String sessionId,
        @JsonView(OrderViews.Admin.class) Long userId,
        @JsonView(OrderViews.Public.class) String deviceId,
        @JsonView({OrderViews.Merchant.class, OrderViews.Admin.class}) String merchantId,
        @JsonView(OrderViews.Public.class) String deviceName,
        @JsonView(OrderViews.Public.class) String merchantName,
        @JsonView(OrderViews.Public.class) int totalAmountCents,
        @JsonView(OrderViews.Public.class) int originalAmountCents,
        @JsonView(OrderViews.Public.class) int couponDiscountCents,
        @JsonView(OrderViews.Public.class) int memberDiscountCents,
        /** CB-082：活动价优惠合计（分）；活动优先禁券，与券抵扣不会同时非零。 */
        @JsonView(OrderViews.Public.class) int activityDiscountCents,
        /**
         * MA-023②/024：全部优惠前毛额（分，落库列 {@code gross_amount_cents}）。
         * 三端明细据此才能把「原价 − 会员 − 活动 − 券 = 实付」摆平；0 表示历史单未回填前的旧值。
         */
        @JsonView(OrderViews.Public.class) int grossAmountCents,
        @JsonView(OrderViews.Public.class) String status,
        @JsonView(OrderViews.Public.class) String payChannel,
        @JsonView(OrderViews.Public.class) int lineCount,
        @JsonView(OrderViews.Public.class) String lineSummary,
        /** 商品行 SKU ID（`、` 分隔，与 lineSummary 同序）——运营台订单列表 SKU 列数据源。 */
        @JsonView(OrderViews.Public.class) String lineSkuIds,
        @JsonView(OrderViews.Public.class) String payTradeNo,
        @JsonView(OrderViews.Public.class) String paymentOperationId,
        @JsonView(OrderViews.Public.class) Instant refundedAt,
        @JsonView(OrderViews.Public.class) int refundedCents,
        @JsonView(OrderViews.Admin.class) boolean inventoryDeducted,
        @JsonView(OrderViews.Public.class) String refundPolicy,
        @JsonView(OrderViews.Public.class) Instant createdAt,
        @JsonView({OrderViews.Merchant.class, OrderViews.Admin.class, OrderViews.Consumer.class}) Instant paidAt,
        @JsonView({OrderViews.Merchant.class, OrderViews.Admin.class}) String splitStatus,
        @JsonView(OrderViews.Public.class) List<OrderLineDto> lines,
        @JsonView({OrderViews.Consumer.class, OrderViews.Admin.class}) Integer balanceBeforeCents,
        @JsonView({OrderViews.Consumer.class, OrderViews.Admin.class}) Integer balanceAfterCents
) {
}
