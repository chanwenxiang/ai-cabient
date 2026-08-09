package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicabinet.replenishment.rop")
public record RopProperties(
        int leadTimeDays,
        int safetyDays,
        boolean trendForecastEnabled,
        int forecastWindowDays,
        int trendLookbackDays,
        double safetyServiceLevel
) {
    public RopProperties() {
        this(2, 1, true, 28, 14, 0.95);
    }

    public int coverDays() {
        return Math.max(1, leadTimeDays + safetyDays);
    }
}
