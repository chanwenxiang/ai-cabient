package com.aicabinet.common.dto;

import java.math.BigDecimal;

public record UserBehaviorRowDto(
        Long userId,
        String phone,
        String name,
        int orderCount,
        BigDecimal totalSpent,
        BigDecimal lastOrderAt
) {}
