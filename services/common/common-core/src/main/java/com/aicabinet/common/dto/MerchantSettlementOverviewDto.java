package com.aicabinet.common.dto;

import java.util.List;

public record MerchantSettlementOverviewDto(
        long pendingAmountCents,
        long pendingSplitCount,
        long settledMonthCents,
        long failedSplitCount,
        ProfitSharingStatusDto profitSharing,
        List<RevenueSplitDto> recentFailures
) {}
