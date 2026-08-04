package com.aicabinet.common.dto;

import java.util.List;

public record MerchantAnalyticsOverviewDto(
        int days,
        long revenueCents,
        long cogsCents,
        long grossMarginCents,
        long writeOffCostCents,
        List<MerchantSkuSalesDto> topSkus
) {}
