package com.aicabinet.common.dto;

import java.time.Instant;

public record InvoiceRequestDto(
        Long invoiceId,
        String orderId,
        Long userId,
        String title,
        String taxNo,
        String email,
        int amountCents,
        String status,
        String rejectReason,
        Instant createdAt,
        Instant issuedAt
) {}
