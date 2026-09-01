package com.aicabinet.common.dto;

/**
 * 销售报表筛选条件下的合计（全量，非本页）。
 */
public record SalesReportSummaryDto(
        long rowCount,
        long orderCount,
        long qty,
        long revenueCents,
        long refundedCents,
        long refundOrderCount,
        long netRevenueCents,
        long cogsCents,
        long marginCents
) {
    public static SalesReportSummaryDto from(java.util.List<SalesReportRowDto> rows) {
        if (rows == null || rows.isEmpty()) {
            return new SalesReportSummaryDto(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        long orderCount = 0;
        long qty = 0;
        long revenue = 0;
        long refunded = 0;
        long refundOrders = 0;
        long cogs = 0;
        long margin = 0;
        for (SalesReportRowDto r : rows) {
            orderCount += r.orderCount();
            qty += r.qty();
            revenue += r.revenueCents();
            refunded += r.refundedCents();
            refundOrders += r.refundOrderCount();
            cogs += r.cogsCents();
            margin += r.marginCents();
        }
        return new SalesReportSummaryDto(
                rows.size(),
                orderCount,
                qty,
                revenue,
                refunded,
                refundOrders,
                Math.max(0L, revenue - refunded),
                cogs,
                margin
        );
    }
}
