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
        String category,
        Integer refundedAmountCents,
        String deviceName,
        Integer claimedAmountCents,
        /** 会话是否已有录像（列表「有录像」提示） */
        Boolean hasVideo
) {
    public MerchantDisputeSummaryDto(
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
    ) {
        this(ticketId, sessionId, deviceId, reason, status, createdAt, resolvedAt, orderId,
                billedAmountCents, slaDueAt, slaOverdue, slaHoursRemaining, category, null, null, null, null);
    }

    public MerchantDisputeSummaryDto(
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
            String category,
            Integer refundedAmountCents
    ) {
        this(ticketId, sessionId, deviceId, reason, status, createdAt, resolvedAt, orderId,
                billedAmountCents, slaDueAt, slaOverdue, slaHoursRemaining, category,
                refundedAmountCents, null, null, null);
    }

    public MerchantDisputeSummaryDto(
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
            String category,
            Integer refundedAmountCents,
            String deviceName
    ) {
        this(ticketId, sessionId, deviceId, reason, status, createdAt, resolvedAt, orderId,
                billedAmountCents, slaDueAt, slaOverdue, slaHoursRemaining, category,
                refundedAmountCents, deviceName, null, null);
    }

    public MerchantDisputeSummaryDto(
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
            String category,
            Integer refundedAmountCents,
            String deviceName,
            Integer claimedAmountCents
    ) {
        this(ticketId, sessionId, deviceId, reason, status, createdAt, resolvedAt, orderId,
                billedAmountCents, slaDueAt, slaOverdue, slaHoursRemaining, category,
                refundedAmountCents, deviceName, claimedAmountCents, null);
    }
}
