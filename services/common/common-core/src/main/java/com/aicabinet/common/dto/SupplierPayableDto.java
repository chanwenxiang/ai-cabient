package com.aicabinet.common.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record SupplierPayableDto(
        Long payableId,
        String supplierId,
        String supplierName,
        Long purchaseOrderId,
        String warehouseId,
        String warehouseName,
        long amountCents,
        long paidAmountCents,
        long balanceCents,
        String status,
        LocalDate dueDate,
        boolean overdue,
        int overdueDays,
        String notes,
        Instant createdAt,
        List<SupplierPaymentDto> payments
) {}
