package com.aicabinet.common.dto;

import java.time.Instant;

public record RepairTicketDto(
        Long ticketId,
        String deviceId,
        String title,
        String faultType,
        String status,
        String assignee,
        String priority,
        String remark,
        Long createdBy,
        Instant createdAt,
        Instant updatedAt,
        Instant closedAt
) {}
