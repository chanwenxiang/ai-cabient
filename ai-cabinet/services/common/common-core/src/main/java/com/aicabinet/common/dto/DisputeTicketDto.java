package com.aicabinet.common.dto;

import java.time.Instant;
import java.util.List;

public record DisputeTicketDto(
        String ticketId,
        String sessionId,
        String reason,
        String status,
        List<OrderLineDto> suggestedItems,
        Instant createdAt,
        String videoUri,
        String videoPreviewUrl
) {}
