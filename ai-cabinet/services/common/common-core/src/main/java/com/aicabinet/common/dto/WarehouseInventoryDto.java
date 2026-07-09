package com.aicabinet.common.dto;

import java.time.LocalDate;

public record WarehouseInventoryDto(
        Long inventoryId,
        String warehouseId,
        String skuId,
        String batchNo,
        LocalDate productionDate,
        LocalDate expiryDate,
        int quantity
) {}
