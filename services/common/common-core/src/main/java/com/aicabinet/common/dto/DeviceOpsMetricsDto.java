package com.aicabinet.common.dto;

import java.time.Instant;

public record DeviceOpsMetricsDto(
        String deviceId,
        int configuredSlotCount,
        int activeSlotCount,
        int fillRatePct,
        int oosRatePct,
        int oosSkuCount,
        int lowStockSkuCount,
        int totalBookQty,
        int totalParLevel,
        Instant lastRestockAt,
        String address,
        Integer currentTempC,
        Integer targetTempC,
        Instant tempReportedAt,
        boolean salesLocked,
        String appVersion,
        String firmwareVersion,
        String alertContactName,
        String alertContactPhone,
        int nearExpiryLotCount,
        String merchantContactName,
        String merchantContactPhone,
        long monthRevenueCents
) {}
