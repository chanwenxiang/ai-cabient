package com.aicabinet.common.dto;

import java.util.List;

public record MerchantWorkbenchDto(
        long openDisputes,
        long offlineDevices,
        long lowStockItems,
        long expiryAlerts,
        long slotDiscrepancies,
        long pendingSplits,
        List<OpsActionItemDto> actionItems
) {}
