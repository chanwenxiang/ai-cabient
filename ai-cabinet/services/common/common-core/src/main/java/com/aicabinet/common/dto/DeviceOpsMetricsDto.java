package com.aicabinet.common.dto;

import java.time.Instant;

public record DeviceOpsMetricsDto(
        String deviceId,
        int configuredSlotCount,
        int activeSlotCount,
        int fillRatePct,
        int oosRatePct,
        int oosSlotCount,
        int lowStockSlotCount,
        int totalBookQty,
        int totalParLevel,
        Instant lastRestockAt,
        int inventoryAccuracyPct
) {}
