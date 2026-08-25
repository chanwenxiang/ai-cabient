package com.aicabinet.common.dto;

import java.math.BigDecimal;

public record MemberLevelRuleDto(
        Long id,
        String levelCode,
        String levelName,
        BigDecimal minSpent,
        BigDecimal maxSpent,
        int minPoints,
        Integer maxPoints,
        BigDecimal pointsRate,
        BigDecimal priceDiscountPct,
        int sortOrder,
        String status
) {}
