package com.aicabinet.common.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record UserBehaviorRowDto(
        Long userId,
        String phone,
        String name,
        int orderCount,
        /** 累计消费（元，兼容旧前端） */
        BigDecimal totalSpent,
        /** 累计消费（分） */
        Long totalSpentCents,
        Instant lastOrderAt
) {
    /** 兼容旧调用：lastOrderAt 传 epoch 毫秒的 BigDecimal */
    public UserBehaviorRowDto(
            Long userId,
            String phone,
            String name,
            int orderCount,
            BigDecimal totalSpent,
            BigDecimal lastOrderAtEpochMs
    ) {
        this(
                userId,
                phone,
                name,
                orderCount,
                totalSpent,
                totalSpent == null
                        ? null
                        : totalSpent.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValue(),
                lastOrderAtEpochMs == null
                        ? null
                        : Instant.ofEpochMilli(lastOrderAtEpochMs.longValue())
        );
    }
}
