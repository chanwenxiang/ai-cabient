package com.aicabinet.common.dto;

import java.time.Instant;

public record OpsExceptionActionDto(
        Long actionId, Long operatorId, String action, String detail, Instant createdAt
) {}
