package com.aicabinet.common.dto;

import java.time.Instant;
import java.util.List;

public record OrderDto(
        String orderId,
        String sessionId,
        Long userId,
        String deviceId,
        int totalAmountCents,
        List<OrderLineDto> lines,
        String status,
        Instant createdAt
) {}
