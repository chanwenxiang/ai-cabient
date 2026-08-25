package com.aicabinet.common.dto;

import java.time.Instant;

public record SupplierPaymentDto(
        Long paymentId,
        String supplierId,
        Long payableId,
        long amountCents,
        Long operatorId,
        String notes,
        Instant createdAt
) {}
