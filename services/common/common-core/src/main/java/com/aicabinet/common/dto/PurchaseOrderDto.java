package com.aicabinet.common.dto;

import java.time.Instant;
import java.util.List;

public record PurchaseOrderDto(
        Long purchaseOrderId,
        String supplierId,
        String warehouseId,
        String status,
        String refNo,
        Long operatorId,
        String notes,
        Instant createdAt,
        Instant receivedAt,
        List<PurchaseOrderLineDto> lines
) {}
