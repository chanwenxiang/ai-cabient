package com.aicabinet.common.dto;

import java.time.Instant;

public record WriteOffDto(
        Long writeOffId,
        String deviceId,
        String skuId,
        String batchNo,
        int quantity,
        String reason,
        Integer costCents,
        Long operatorId,
        Instant createdAt
) {}
