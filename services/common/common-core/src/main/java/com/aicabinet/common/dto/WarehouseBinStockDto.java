package com.aicabinet.common.dto;

import java.time.LocalDate;

public record WarehouseBinStockDto(
        Long id,
        Long binId,
        String warehouseId,
        String binCode,
        String skuId,
        String skuName,
        String batchNo,
        LocalDate productionDate,
        LocalDate expiryDate,
        int quantity
) {}
