package com.aicabinet.common.dto;

import java.time.Instant;

public record AdminAuditLogDto(
        Long logId,
        Long operatorId,
        String operatorPhone,
        String operatorName,
        String action,
        String targetType,
        String targetId,
        String detail,
        Instant createdAt
) {}
