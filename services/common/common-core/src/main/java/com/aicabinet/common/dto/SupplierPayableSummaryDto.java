package com.aicabinet.common.dto;

public record SupplierPayableSummaryDto(
        String supplierId,
        String supplierName,
        int payableCount,
        long totalBalanceCents,
        long overdueBalanceCents
) {}
