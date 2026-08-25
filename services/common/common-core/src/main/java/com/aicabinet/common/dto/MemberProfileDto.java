package com.aicabinet.common.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record MemberProfileDto(
        Long memberId,
        Long userId,
        String levelCode,
        String levelName,
        BigDecimal totalSpent,
        int availablePoints,
        int totalPoints,
        int orderCount,
        int spentToNextLevel,
        String nextLevelName,
        double progressPercent,
        List<MemberLevelRuleDto> levels,
        Instant createdAt
) {}
