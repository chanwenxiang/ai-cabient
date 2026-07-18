package com.aicabinet.common.dto;

import java.time.Instant;
import java.util.List;

public record PurchaseReturnDto(
        Long returnId,
        Long purchaseOrderId,
        String warehouseId,
        String supplierId,
        String status,
        String notes,
        Long operatorId,
        Instant createdAt,
        List<PurchaseReturnLineDto> lines
) {}
