package com.aicabinet.common.dto;

import java.math.BigDecimal;

public record MemberPointsSummaryDto(
        int availablePoints,
        int totalPoints,
        int usedPoints,
        int expiredPoints,
        String levelCode,
        String levelName,
        BigDecimal pointsRate,
        int nextLevelPointsGap
) {}
