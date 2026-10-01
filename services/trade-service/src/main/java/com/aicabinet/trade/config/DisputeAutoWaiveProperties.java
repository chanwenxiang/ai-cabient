package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * P3-4 争议超时自动免单（docs/P3_4_DISPUTE_AUTO_WAIVE_DESIGN.md）。
 * 🔴 enabled 默认 false（fail-closed）：dev 先开观察，production 评审后再开。
 */
@ConfigurationProperties(prefix = "aicabinet.dispute-auto-waive")
public record DisputeAutoWaiveProperties(
        boolean enabled,
        int hours,
        int maxPerRound,
        int perUserMax,
        int perUserWindowDays
) {
    public DisputeAutoWaiveProperties {
        if (hours <= 0) {
            hours = 72;
        }
        if (maxPerRound <= 0) {
            maxPerRound = 50;
        }
        if (perUserMax <= 0) {
            perUserMax = 3;
        }
        if (perUserWindowDays <= 0) {
            perUserWindowDays = 7;
        }
    }
}
