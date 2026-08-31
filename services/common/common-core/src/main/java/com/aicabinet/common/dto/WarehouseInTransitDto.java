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
        Instant receivedAt,
        String deviceName
) {
    public WarehouseInTransitDto(
            Long transitId,
            Long outboundId,
            String deviceId,
            String skuId,
            String batchNo,
            int quantity,
            String status,
            Instant createdAt,
            Instant receivedAt
    ) {
        this(transitId, outboundId, deviceId, skuId, batchNo, quantity, status, createdAt, receivedAt, null);
    }
}
