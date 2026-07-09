package com.aicabinet.common.dto;

import java.time.LocalDate;

public record WarehouseInboundLineDto(
        String skuId,
        String batchNo,
        LocalDate productionDate,
        LocalDate expiryDate,
        int quantity
) {}
