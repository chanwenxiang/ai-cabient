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
    String description,
    /** CB-082：SKU_REDUCE 立减金额（分）。 */
    Integer reduceCents,
    /** CB-082：SKU_DISCOUNT 折扣（万分比，如 8500 = 85 折）。 */
    Integer discountBps,
    /** CB-082：SKU_FIXED_PRICE 一口价（分，最低 1）。 */
    Integer fixedPriceCents
) {}
