package com.aicabinet.common.dto;

import java.time.Instant;

public record DeviceInventoryDto(
        String deviceId,
        String skuId,
        int quantity,
        int capacity,
        int lowThreshold,
        Instant updatedAt
) {}
