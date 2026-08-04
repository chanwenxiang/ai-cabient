package com.aicabinet.common.dto;

public record MerchantOpsConfigDto(
        String merchantId,
        String stockingType,
        int stockoutThresholdPct,
        String tallyMode,
        boolean useStockingList,
        String replenishInputType,
        boolean photoStocktake,
        boolean photoReplenish,
        int maxInflightOrders
) {}
