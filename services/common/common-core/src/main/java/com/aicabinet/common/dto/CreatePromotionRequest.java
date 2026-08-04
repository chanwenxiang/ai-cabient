package com.aicabinet.common.dto;

import java.time.Instant;

public record CreatePromotionRequest(
    String activityName,
    String activityType,
    Instant startTime,
    Instant endTime,
    long budgetCents,
    int userLimit,
    String deviceScope,
    String ruleConfig,
    String description
) {}
