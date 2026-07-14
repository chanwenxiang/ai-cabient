package com.aicabinet.common.dto;

import java.time.Instant;

public record SupplierDto(
        String supplierId,
        String supplierName,
        String contactName,
        String contactPhone,
        String status,
        Instant createdAt
) {}
