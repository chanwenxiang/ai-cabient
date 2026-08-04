package com.aicabinet.common.dto;

import java.math.BigDecimal;

public record MemberLevelRuleDto(
        String levelCode,
        String levelName,
        BigDecimal minSpent,
        BigDecimal maxSpent,
        int sortOrder
) {}
