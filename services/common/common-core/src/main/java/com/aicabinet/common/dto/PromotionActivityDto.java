package com.aicabinet.common.dto;

import java.time.Instant;

public record PromotionActivityDto(
    Long activityId,
    String activityName,
    String activityType,
    String status,
    Instant startTime,
    Instant endTime,
    long budgetCents,
    long usedCents,
    int userLimit,
    String deviceScope,
    String ruleConfig,
    String description
) {}
