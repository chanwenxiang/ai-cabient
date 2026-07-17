package com.aicabinet.common.dto;

public record MemberPointsSummaryDto(
        int availablePoints,
        int totalPoints,
        int usedPoints,
        int expiredPoints,
        int earnedThisMonth,
        int usedThisMonth
) {}
