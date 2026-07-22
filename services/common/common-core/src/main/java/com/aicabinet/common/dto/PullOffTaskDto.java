package com.aicabinet.common.dto;

import java.time.Instant;

public record PullOffTaskDto(
        Long taskId,
        String deviceId,
        String skuId,
        String lotId,
        String batchNo,
        int quantity,
        String reason,
        String status,
        Instant createdAt,
        /** Remaining restock capacity for this SKU on the device; 0 means RESTOCK should be disabled. */
        int restockHeadroom
) {}
