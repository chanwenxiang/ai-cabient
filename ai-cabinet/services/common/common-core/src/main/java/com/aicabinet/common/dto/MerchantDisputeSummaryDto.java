package com.aicabinet.common.dto;

import java.time.Instant;

public record MerchantDisputeSummaryDto(
        String ticketId,
        String sessionId,
        String deviceId,
        String reason,
        String status,
        Instant createdAt,
        Instant resolvedAt,
        String orderId,
        Integer billedAmountCents,
        Instant slaDueAt,
        boolean slaOverdue,
        Long slaHoursRemaining,
        String category
) {}
