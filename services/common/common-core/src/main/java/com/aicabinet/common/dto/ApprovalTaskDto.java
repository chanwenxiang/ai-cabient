package com.aicabinet.common.dto;

import java.time.Instant;

public record ApprovalTaskDto(
        Long taskId,
        Long instanceId,
        String bizType,
        String bizId,
        String title,
        String nodeName,
        Integer nodeSeq,
        String status,
        String actionPath,
        Instant createdAt,
        Instant readAt
) {}
