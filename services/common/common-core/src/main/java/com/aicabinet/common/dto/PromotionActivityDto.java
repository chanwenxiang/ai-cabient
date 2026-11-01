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
    String description,
    /** CB-082 活动定价参数（按 activityType 取其一）。 */
    Integer reduceCents,
    Integer discountBps,
    Integer fixedPriceCents
) {}
