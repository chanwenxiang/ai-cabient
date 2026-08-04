package com.aicabinet.common.dto;

public record AdminStatsDto(
        long deviceTotal,
        long deviceOnline,
        long sessionActive,
        long deviceOccupied,
        long sessionToday,
        long orderToday,
        long revenueTodayCents,
        long orderTotal,
        long revenueTotalCents,
        long disputeOpen,
        long disputeOverdue,
        long disputeNearSla,
        long sessionWaitingUpload,
        double doorSuccessRate24h,
        double disputeRate24h,
        double recognitionAutoRate24h,
        long lowStockSkuCount,
        long pendingSplitCount,
        long nearExpiryLotCount,
        long expiredLotCount,
        long pullOffOpenCount,
        long slotDiscrepancyCount
) {}
