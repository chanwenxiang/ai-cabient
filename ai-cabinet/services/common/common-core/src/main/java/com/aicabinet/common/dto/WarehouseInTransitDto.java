package com.aicabinet.common.dto;

import java.time.Instant;

public record WarehouseInTransitDto(
        Long transitId,
        Long outboundId,
        String deviceId,
        String skuId,
        String batchNo,
        int quantity,
        String status,
        Instant createdAt,
        Instant receivedAt
) {}
