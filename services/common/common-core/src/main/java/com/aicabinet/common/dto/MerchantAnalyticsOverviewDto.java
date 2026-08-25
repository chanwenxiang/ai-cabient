package com.aicabinet.common.dto;

import java.util.List;

public record MerchantAnalyticsOverviewDto(
        int days,
        long revenueCents,
        long cogsCents,
        long grossMarginCents,
        long writeOffCostCents,
        List<MerchantSkuSalesDto> topSkus,
        long orderCount,
        long avgOrderValueCents,
        long itemQtySold,
        long avgUnitPriceCents,
        long prevRevenueCents,
        long prevGrossMarginCents,
        Double revenueChangePct,
        Double marginChangePct,
        int stockoutSkuCount,
        long stockoutLossEstimateCents
) {
    /** 兼容旧 6 字段构造。 */
    public MerchantAnalyticsOverviewDto(
            int days,
            long revenueCents,
            long cogsCents,
            long grossMarginCents,
            long writeOffCostCents,
            List<MerchantSkuSalesDto> topSkus
    ) {
        this(days, revenueCents, cogsCents, grossMarginCents, writeOffCostCents, topSkus,
                0, 0, 0, 0, 0, 0, null, null, 0, 0);
    }
}
