package com.aicabinet.common.dto;

import java.time.Instant;

public record RepairTicketDto(
        Long ticketId,
        String deviceId,
        String deviceName,
        String merchantId,
        String merchantName,
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
) {
    /** 兼容旧构造（无设备名/商户冗余）。 */
    public RepairTicketDto(
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
    ) {
        this(ticketId, deviceId, null, null, null, title, faultType, status, assignee, priority,
                remark, createdBy, createdAt, updatedAt, closedAt);
    }
}
