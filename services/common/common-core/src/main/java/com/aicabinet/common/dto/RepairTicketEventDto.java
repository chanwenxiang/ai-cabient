package com.aicabinet.common.dto;

import java.time.Instant;

public record RepairTicketEventDto(
        Long eventId,
        Long ticketId,
        String fromStatus,
        String toStatus,
        String action,
        Long operatorId,
        String remark,
        Instant createdAt
) {}
