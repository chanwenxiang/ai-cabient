package com.aicabinet.common.dto;

import java.time.Instant;
import java.time.LocalDate;

public record StockHealthRowDto(
        String dimension,
        String deviceId,
        String deviceName,
        String merchantId,
        String routeCode,
        String lifecycleStatus,
        String skuId,
        String skuName,
        int quantity,
        int capacity,
        Integer lowThreshold,
        double stockoutRatePct,
        Integer daysOutOfStock,
        LocalDate expiryDate,
        Instant updatedAt,
        String lotId,
        String batchNo
) {}
