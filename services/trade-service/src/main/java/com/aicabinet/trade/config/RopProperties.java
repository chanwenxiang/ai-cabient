package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicabinet.replenishment.rop")
public record RopProperties(
        int leadTimeDays,
        int safetyDays
) {
    public RopProperties() {
        this(2, 1);
    }

    public int coverDays() {
        return Math.max(1, leadTimeDays + safetyDays);
    }
}
