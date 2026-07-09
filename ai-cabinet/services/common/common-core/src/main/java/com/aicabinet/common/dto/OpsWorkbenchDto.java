package com.aicabinet.common.dto;

import java.util.List;

public record OpsWorkbenchDto(
        long openDisputes,
        long overdueDisputes,
        long offlineDevices,
        long waitingUploads,
        long lowStockItems,
        long pendingReplenishments,
        List<OpsActionItemDto> actionItems
) {}
