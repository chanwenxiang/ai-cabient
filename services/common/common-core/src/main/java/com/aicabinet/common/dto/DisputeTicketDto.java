package com.aicabinet.common.dto;

import java.time.Instant;
import java.util.List;

public record DisputeTicketDto(
        String ticketId,
        String sessionId,
        String deviceId,
        String reason,
        String status,
        List<OrderLineDto> suggestedItems,
        List<OrderLineDto> resolutionItems,
        Instant createdAt,
        Instant resolvedAt,
        String videoUri,
        String videoPreviewUrl,
        String sessionState,
        String orderId,
        Integer billedAmountCents,
        Instant slaDueAt,
        boolean slaOverdue,
        Long slaHoursRemaining,
        String category,
        String priority,
        String operatorNote,
        Instant closedAt,
        Instant reopenedAt,
        List<DisputeMessageDto> messages,
        List<FileAttachmentDto> evidence,
        String reviewCode,
        List<String> detectedClasses
) {}
