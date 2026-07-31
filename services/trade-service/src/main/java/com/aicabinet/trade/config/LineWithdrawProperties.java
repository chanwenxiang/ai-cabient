package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicabinet.line-withdraw")
public record LineWithdrawProperties(
        boolean mockEnabled,
        long minAmountCents,
        long dailyLimitCents,
        long reviewThresholdCents
) {
    public LineWithdrawProperties {
        if (minAmountCents <= 0) {
            minAmountCents = 100;
        }
        if (dailyLimitCents <= 0) {
            dailyLimitCents = 500_000;
        }
        if (reviewThresholdCents <= 0) {
            reviewThresholdCents = 50_000;
        }
    }
}
