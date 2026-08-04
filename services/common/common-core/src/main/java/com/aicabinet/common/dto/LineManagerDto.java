package com.aicabinet.common.dto;

import java.time.Instant;
import java.util.List;

public record LineManagerDto(
        Long managerId,
        String managerName,
        String phone,
        String status,
        String wxOpenid,
        Long userId,
        String orgName,
        Integer commissionRateBps,
        Integer commissionFixedCents,
        Long balanceCents,
        Long frozenCents,
        List<String> deviceIds,
        Instant createdAt,
        Instant updatedAt
) {}
