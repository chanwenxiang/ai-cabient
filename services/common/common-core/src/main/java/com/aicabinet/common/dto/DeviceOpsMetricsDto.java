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
        int inventoryAccuracyPct,
        String address,
        Integer currentTempC,
        Integer targetTempC,
        Instant tempReportedAt,
        boolean salesLocked,
        String appVersion,
        String firmwareVersion,
        String alertContactName,
        String alertContactPhone,
        int nearExpiryLotCount
) {
    /** 兼容旧 12 字段构造 */
    public DeviceOpsMetricsDto(
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
    ) {
        this(deviceId, configuredSlotCount, activeSlotCount, fillRatePct, oosRatePct,
                oosSlotCount, lowStockSlotCount, totalBookQty, totalParLevel, lastRestockAt,
                inventoryAccuracyPct, null, null, null, null, false, null, null, null, null, 0);
    }

    /** 兼容旧 20 字段构造（无临期） */
    public DeviceOpsMetricsDto(
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
            int inventoryAccuracyPct,
            String address,
            Integer currentTempC,
            Integer targetTempC,
            Instant tempReportedAt,
            boolean salesLocked,
            String appVersion,
            String firmwareVersion,
            String alertContactName,
            String alertContactPhone
    ) {
        this(deviceId, configuredSlotCount, activeSlotCount, fillRatePct, oosRatePct,
                oosSlotCount, lowStockSlotCount, totalBookQty, totalParLevel, lastRestockAt,
                inventoryAccuracyPct, address, currentTempC, targetTempC, tempReportedAt,
                salesLocked, appVersion, firmwareVersion, alertContactName, alertContactPhone, 0);
    }
}
