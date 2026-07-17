package com.aicabinet.common.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record MemberProfileDto(
        Long memberId,
        Long userId,
        String levelCode,
        String levelName,
        int availablePoints,
        int totalPoints,
        int usedPoints,
        BigDecimal totalSpent,
        int orderCount,
        String inviteCode,
        int pointsToNextLevel,
        String nextLevelName,
        double progressPercent,
        double pointsRate,
        List<MemberLevelRuleDto> levels,
        Instant createdAt
) {}
