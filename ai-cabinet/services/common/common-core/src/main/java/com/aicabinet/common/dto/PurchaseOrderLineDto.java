package com.aicabinet.common.dto;

import java.time.LocalDate;

public record PurchaseOrderLineDto(
        Long lineId,
        String skuId,
        String batchNo,
        LocalDate productionDate,
        LocalDate expiryDate,
        int orderedQty,
        int receivedQty,
        int unitCostCents
) {}
