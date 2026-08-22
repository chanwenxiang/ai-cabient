package com.aicabinet.common.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record WarehouseTransferDto(
        Long transferId,
        String transferNo,
        String fromWarehouseId,
        String toWarehouseId,
        String status,
        String notes,
        Long operatorId,
        Instant shippedAt,
        Instant receivedAt,
        Instant createdAt,
        List<WarehouseTransferLineDto> lines
) {
    public record WarehouseTransferLineDto(
            Long lineId,
            String skuId,
            String batchNo,
            LocalDate expiryDate,
            int quantity
    ) {}
}
